import assert from 'node:assert/strict';
import test from 'node:test';
import {ConversationPauseRegistry} from '../src/platform/pauseRegistry.ts';

test('an in-flight reply is discarded when the user pauses during generation', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'job-1';

    const turn = ConversationPauseRegistry.beginTurn(jobKey);
    // 用户在模型生成期间手动介入。
    ConversationPauseRegistry.pause(jobKey);

    assert.equal(ConversationPauseRegistry.canDeliver(jobKey, turn), false);
    assert.equal(ConversationPauseRegistry.isPaused(jobKey), true);
});

test('a resumed conversation does not resurrect the reply generated before the pause', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'job-1';

    const turn = ConversationPauseRegistry.beginTurn(jobKey);
    ConversationPauseRegistry.pause(jobKey);
    ConversationPauseRegistry.resume(jobKey);

    assert.equal(ConversationPauseRegistry.isPaused(jobKey), false);
    // 代次已经前进，暂停前那一轮仍然过期。
    assert.equal(ConversationPauseRegistry.canDeliver(jobKey, turn), false);
});

test('a reply generated after the resume is deliverable', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'job-1';

    ConversationPauseRegistry.pause(jobKey);
    ConversationPauseRegistry.resume(jobKey);
    const freshTurn = ConversationPauseRegistry.beginTurn(jobKey);

    assert.equal(ConversationPauseRegistry.canDeliver(jobKey, freshTurn), true);
});

test('an untouched conversation keeps delivering normally', () => {
    ConversationPauseRegistry.reset();
    const turn = ConversationPauseRegistry.beginTurn('job-x');

    assert.equal(ConversationPauseRegistry.canDeliver('job-x', turn), true);
});

test('pausing one conversation does not affect another account or job', () => {
    ConversationPauseRegistry.reset();
    const turn = ConversationPauseRegistry.beginTurn('job-b');
    ConversationPauseRegistry.pause('job-a');

    assert.equal(ConversationPauseRegistry.canDeliver('job-a', turn), false);
    assert.equal(ConversationPauseRegistry.canDeliver('job-b', turn), true);
});

test('the registry never throws without a job key', () => {
    ConversationPauseRegistry.reset();
    assert.doesNotThrow(() => {
        const turn = ConversationPauseRegistry.beginTurn('');
        ConversationPauseRegistry.pause('');
        ConversationPauseRegistry.canDeliver('', turn);
        ConversationPauseRegistry.resume('');
    });
});

test('adversarial: repeated pause/resume cannot desynchronise the generation', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'flap-job';

    // 用户反复切换暂停/恢复：早期轮次必须始终作废。
    const earlyTurn = ConversationPauseRegistry.beginTurn(jobKey);
    for (let index = 0; index < 5; index += 1) {
        ConversationPauseRegistry.pause(jobKey);
        ConversationPauseRegistry.resume(jobKey);
    }
    assert.equal(ConversationPauseRegistry.canDeliver(jobKey, earlyTurn), false);

    // 稳定后新开的一轮可以正常发送。
    const freshTurn = ConversationPauseRegistry.beginTurn(jobKey);
    assert.equal(ConversationPauseRegistry.canDeliver(jobKey, freshTurn), true);
});

test('adversarial: an account switch cannot inherit another account pause state', () => {
    ConversationPauseRegistry.reset();
    // A 账号暂停。
    ConversationPauseRegistry.pause('acctA:job-1');
    // B 账号的同一岗位不受影响。
    const turnB = ConversationPauseRegistry.beginTurn('acctB:job-1');
    assert.equal(ConversationPauseRegistry.canDeliver('acctB:job-1', turnB), true);
});

console.log('conversation pause smoke: PASS');
