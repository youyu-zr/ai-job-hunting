import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';
import {
    canReplayAfterRelogin,
    recoverSession,
    registerSessionRecovery,
    resetSessionRecoveryForTest,
} from '../src/utils/sessionRecovery.ts';

test('concurrent recoveries share one relogin', async () => {
    resetSessionRecoveryForTest();
    let calls = 0;
    let ready = false;
    registerSessionRecovery(async () => {
        calls += 1;
        await new Promise(resolve => setTimeout(resolve, 5));
        ready = true;
    }, () => ready);
    const results = await Promise.all([recoverSession(), recoverSession(), recoverSession()]);
    assert.deepEqual(results, [true, true, true]);
    assert.equal(calls, 1);
});

test('a failed recovery backs off instead of hammering the server', async () => {
    resetSessionRecoveryForTest();
    let calls = 0;
    registerSessionRecovery(async () => {
        calls += 1;
        throw new Error('server down');
    }, () => false);
    assert.equal(await recoverSession(), false);
    assert.equal(await recoverSession(), false);
    assert.equal(calls, 1);
    // 30 秒后才允许再试
    assert.equal(await recoverSession(Date.now() + 31_000), false);
    assert.equal(calls, 2);
});

test('only ordinary requests are replayed, once', () => {
    assert.equal(canReplayAfterRelogin({url: '/api/ai/job/seeker/cloned/ask'}), true);
    assert.equal(canReplayAfterRelogin({url: '/api/ai/job/seeker/cloned/ask', __retriedAfterRelogin: true}), false);
    assert.equal(canReplayAfterRelogin({url: '/api/user/userinfo'}), false);
    assert.equal(canReplayAfterRelogin({url: '/api/user/silently/login?uniqueId=1'}), false);
    assert.equal(canReplayAfterRelogin({url: '/api/user/import/resume', bossIdentityContext: {}}), false);
});

test('axios relogins on 401 and the backend session slides', () => {
    const axiosSource = readFileSync(new URL('../src/axios.ts', import.meta.url), 'utf8');
    assert.equal((axiosSource.match(/return replayAfterRelogin\(/g) || []).length, 2);
    assert.match(axiosSource, /__retriedAfterRelogin: true/);
    const remoteSource = readFileSync(new URL('../src/stores/remote.ts', import.meta.url), 'utf8');
    assert.match(remoteSource, /registerSessionRecovery\(\(\) => userRemoteLoad\(\), isApplicationSessionReady\)/);
    const cache = readFileSync(new URL(
        '../../backend/src/main/java/com/maple/ai/job/hunting/frame/cache/SysSessionCache.java', import.meta.url), 'utf8');
    assert.match(cache, /super\(2, TimeUnit\.HOURS, true\)/);
});

test('unread scanner self-heals: relogin, watchdog, wake on visible', () => {
    const source = readFileSync(new URL('../src/platform/bossPlatform.ts', import.meta.url), 'utf8');
    const start = source.indexOf('private static async scanUnreadConversationOnce');
    const body = source.slice(start, source.indexOf('getGlobalAiSeatStatus', start));
    assert.match(body, /isApplicationSessionReady\(\)[\s\S]*recoverSession\(\)/);
    assert.match(body, /UNREAD_SCAN_STUCK_MS/);
    assert.match(source, /visibilitychange[\s\S]{0,120}visibilityState === 'visible'/);
    assert.match(source, /if \(BossOption\.unreadScanStartedAt === scanStartedAt\)/);
});

test('contact sharing is an explicit, default-off switch on the preference page', () => {
    const page = readFileSync(new URL('../src/components/ui/Preference.vue', import.meta.url), 'utf8');
    const remote = readFileSync(new URL('../src/stores/remote.ts', import.meta.url), 'utf8');
    assert.match(page, /v-model="userStore\.user\.preference\.contactShareE"/);
    assert.match(page, /preference\.contactShareE = preference\.contactShareE \?\? false/);
    assert.match(remote, /preference\.contactShareE = preference\.contactShareE \?\? false/);
});
