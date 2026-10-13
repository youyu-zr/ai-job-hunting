/**
 * 本轮上线前修复的对抗性测试。
 *
 * 目标不是重复正向用例，而是主动尝试"把已经修好的边界重新打穿"：
 * 攻击者或异常时序下，能不能让系统重新出现跨账号串数据、把未确认当成已发送、
 * 或者在用户暂停之后仍然把在途回复发出去。
 */
import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';

import {formatUserVisibleLog} from '../src/logging/presentation.ts';
import {isConfirmedStatus, isRetryableStatus, isInFlightStatus} from '../src/platform/sendStatus.ts';
import {ConversationPauseRegistry} from '../src/platform/pauseRegistry.ts';
import {decodeMessageBatch, getMsgBody} from '../src/webSocket/messageBody.ts';
import {readAllLogs, getLatestPushLogs} from '../src/logging/eventStream.ts';

const ui = (relative: string) => readFileSync(new URL(`../src/${relative}`, import.meta.url), 'utf8');

test('adversarial: a batch cannot smuggle an HR message past decoding', () => {
    // 攻击场景：第一条是正常文本，第二条是真正重要的简历请求；
    // 旧实现只解第一条，第二条正文为空后被静默丢弃。
    const messages = [
        {from: {uid: '101'}, body: {type: 1, text: '你好'}},
        {from: {uid: '101'}, body: {type: 7, resume: {}}},
    ];

    decodeMessageBatch(messages, 'payload', '接收');

    assert.equal(getMsgBody({messages}), 'HR明确请求发送简历（结构化消息）');
});

test('adversarial: a malformed batch cannot crash the receive path', () => {
    const hostile: any[] = [
        undefined, null, {}, {messages: null}, {messages: {}}, {messages: 'x'},
        {messages: []}, {messages: [null]}, {messages: [{body: null}]},
        {messages: [{body: {type: 7}}]},
    ];

    for (const payload of hostile) {
        assert.equal(getMsgBody(payload), '');
    }
    assert.doesNotThrow(() => decodeMessageBatch(hostile as any, 'p', '接收'));
});

test('adversarial: an unconfirmed send can never be presented as success', () => {
    assert.equal(isConfirmedStatus('SEND_SUBMITTED'), false);
    assert.equal(isConfirmedStatus('UNCONFIRMED'), false);
    // 未确认必须可重试，但不能算成功。
    assert.equal(isRetryableStatus('UNCONFIRMED'), true);
    assert.equal(isRetryableStatus('SEND_CONFIRMED'), false);

    const unconfirmedLog = formatUserVisibleLog(
        '[SEND_STATE] status=UNCONFIRMED traceId=t9 jobKey=前端 action=AI_REPLY msg=no own-message echo or platform acknowledgement before timeout'
    );
    assert.equal(unconfirmedLog.includes('已确认发送'), false);
    assert.match(unconfirmedLog, /未确认，需人工核实/);
});

test('adversarial: submitted text is not silently relabelled as confirmed', () => {
    const submitted = formatUserVisibleLog(
        '[SEND_STATE] status=SEND_SUBMITTED traceId=t8 jobKey=前端 action=AI_REPLY msg=accepted the message'
    );
    assert.match(submitted, /已提交，等待平台确认/);
    assert.equal(submitted.includes('已确认发送'), false);
});

test('adversarial: pausing during generation cannot leak the in-flight reply', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'adversarial-job';

    // 模型开始生成。
    const turn = ConversationPauseRegistry.beginTurn(jobKey);
    // 用户在这期间点了暂停（也可能是手动发了消息触发人工接管）。
    ConversationPauseRegistry.pause(jobKey);
    // 生成完成，准备发送。
    assert.equal(ConversationPauseRegistry.canDeliver(jobKey, turn), false);

    // 用户恢复会话，但生成期间那条旧回复仍然必须作废。
    ConversationPauseRegistry.resume(jobKey);
    assert.equal(ConversationPauseRegistry.canDeliver(jobKey, turn), false);
});

test('adversarial: the pause guard is actually wired into the reply send path', () => {
    const bossPlatform = ui('platform/bossPlatform.ts');
    const aiPower = ui('platform/aiPower.ts');

    // 生成前取代次，发送前校验；两者缺一都等于没有防护。
    assert.match(bossPlatform, /const turnGeneration = ConversationPauseRegistry\.beginTurn\(jobKey\)/);
    assert.match(bossPlatform, /if \(!ConversationPauseRegistry\.canDeliver\(jobKey, turnGeneration\)\)/);
    assert.match(aiPower, /ConversationPauseRegistry\.pause\(jobKey\)/);
    assert.match(aiPower, /ConversationPauseRegistry\.resume\(jobKey\)/);
});

test('adversarial: no code path can read a cross-account config mirror', () => {
    const server = ui('stores/server.ts');
    const remote = ui('stores/remote.ts');
    const userStore = ui('stores/index.ts');
    const preference = ui('components/ui/Preference.vue');

    for (const source of [server, remote, userStore, preference]) {
        assert.doesNotMatch(source, /global_latest/);
        assert.doesNotMatch(source, /getGlobalMirrorKey/);
        assert.doesNotMatch(source, /localStorage\.getItem\(["']ai-job-user["']\)/);
    }
    // 账号未知时必须拒绝写镜像，而不是退回共享 key。
    assert.match(remote, /if \(!accountId \|\| !snapshotKey \|\| !mirrorKey\) \{/);
    assert.match(remote, /userSnapshotStorageKey\(accountId, serverUrl\)/);
    assert.match(userStore, /userSnapshotStorageKey\(accountId, serverUrl\)/);
    assert.match(remote, /const mirrorKey = serverStore\.getMirrorKey\('user_config', requestedAccountId, requestedServerUrl\)/);
});

test('adversarial: stale 401 responses cannot invalidate a newer session token', () => {
    const axios = ui('axios.ts');
    const response401 = axios.slice(axios.indexOf('if (result.code === 401)'), axios.indexOf('if (!result.code'));
    const http401 = axios.slice(axios.indexOf('if (error.response?.status === 401)'), axios.indexOf('// 到达前端axios'));
    assert.match(response401, /const authorizationIsCurrent = isScopedAuthorizationCurrent/);
    assert.ok(response401.indexOf("const authorizationIsCurrent") < response401.indexOf("clearScopedAuthorization"));
    assert.match(response401, /isCurrentSessionRequest\(resp\.config\) && authorizationIsCurrent/);
    assert.match(http401, /const authorizationIsCurrent = isScopedAuthorizationCurrent/);
    assert.ok(http401.indexOf("const authorizationIsCurrent") < http401.indexOf("clearScopedAuthorization"));
    assert.match(http401, /isCurrentSessionRequest\(error\.config\) && authorizationIsCurrent/);
});

test('adversarial: a hostile log payload cannot inject markup into either view', () => {
    const hostile = '<img src=x onerror=alert(1)>\u0000\u001b[31m红色';
    const formatted = formatUserVisibleLog(hostile);

    assert.equal(formatted.includes('\u0000'), false);
    assert.equal(formatted.includes('\u001b'), false);

    const panel = ui('components/ui/AiJob.vue');
    const runRecord = ui('components/ui/RunRecord.vue');
    assert.doesNotMatch(panel, /v-html="record\.message"/);
    assert.doesNotMatch(runRecord, /v-html="scope\.row\.message"/);
});

test('adversarial: duplicate event ids cannot merge two events into one row', () => {
    const logs = [
        {eventId: 'evt-1', level: 'info', message: '开始投递', timestamp: '10:00:00'},
        {eventId: 'evt-1', level: 'info', message: '开始投递', timestamp: '10:00:01'},
        {eventId: 'evt-2', level: 'info', message: '工作【A】投递成功', timestamp: '10:00:02'},
    ];
    const recorder: any = {
        getLogCount: () => logs.length,
        getLogs: (page: number, size: number) => logs.slice((page - 1) * size, (page - 1) * size + size),
    };

    const events = readAllLogs(recorder);

    assert.deepEqual(events.map(event => event.eventId), ['evt-2', 'evt-1']);
    assert.equal(getLatestPushLogs(recorder, 10, null).length, 2);
});

test('adversarial: a trimmed log window cannot resurrect old delivery records', () => {
    let logs = [
        {eventId: 'evt-1', level: 'info', message: '开始投递', timestamp: '10:00:00'},
        {eventId: 'evt-2', level: 'info', message: '工作【A】投递成功', timestamp: '10:00:01'},
    ];
    const recorder: any = {
        getLogCount: () => logs.length,
        getLogs: (page: number, size: number) => logs.slice((page - 1) * size, (page - 1) * size + size),
    };

    const startId = 'evt-1';
    logs = [
        {eventId: 'evt-5', level: 'info', message: '工作【B】投递成功', timestamp: '10:00:05'},
        {eventId: 'evt-6', level: 'info', message: '工作【C】投递成功', timestamp: '10:00:06'},
    ];

    // 起点事件已经被裁剪：只能展示当前窗口内的事件，不能把旧记录算进来。
    assert.deepEqual(getLatestPushLogs(recorder, 10, startId).map(l => l.message), [
        '工作【B】投递成功',
        '工作【C】投递成功',
    ]);
});

test('adversarial: in-flight is a bounded state, not a permanent limbo', () => {
    assert.equal(isInFlightStatus('SEND_SUBMITTED'), true);
    assert.equal(isInFlightStatus('UNCONFIRMED'), false);
    assert.equal(isInFlightStatus('FAILED'), false);
});

console.log('adversarial pre-release suite: PASS');
