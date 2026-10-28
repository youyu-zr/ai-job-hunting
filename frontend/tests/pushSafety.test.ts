import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

// 上线前审核（2026-10-29）发现的封号风险，逐条锁住。
const platform = readFileSync(new URL('../src/platform/platform.ts', import.meta.url), 'utf8');
const aiJob = readFileSync(new URL('../src/components/ui/AiJob.vue', import.meta.url), 'utf8');

test('a BOSS limit/remind dialog stops the whole round instead of being swallowed', () => {
    // String has no .include(); the old typo threw and the loop kept pushing after BOSS said stop.
    assert.doesNotMatch(platform, /\.content\.include\(/);
    const doPush = platform.slice(platform.indexOf('async doPush('), platform.indexOf('async pushAfterHandler('));
    assert.match(doPush, /remindContent\.includes\("您今天已与120位BOSS沟通"\)/);
    assert.match(doPush, /throw new PublishLimitExp\("BOSS 提示：" \+ remindContent\)/);
});

test('the daily cap counts persisted successes, so pressing start again cannot bypass it', () => {
    const isLimit = platform.slice(platform.lastIndexOf('isLimit(jobDetail: JobDetail)'), platform.indexOf('async doPush('));
    assert.match(isLimit, /PUSH_SUCCESS_COUNT/);
    assert.match(isLimit, /todaySuccess >= DAILY_PUSH_LIMIT/);
    assert.doesNotMatch(isLimit, /TampermonkeyApi\.PUSH_LIMIT\b/);
});

test('push waits are jittered rather than a fixed machine rhythm', () => {
    assert.match(platform, /preference\.pi \* 1000 \+ Math\.floor\(Math\.random\(\) \* 2000\)/);
});

test('only one push loop can run at a time', () => {
    const start = platform.slice(platform.indexOf('async startPush()'), platform.indexOf('private async runPushLoop()'));
    assert.match(start, /if \(this\._pushLoopRunning\)/);
    assert.match(start, /finally \{\s*this\._pushLoopRunning = false;/);
});

test('a crashed push round restores the button and tells the user', () => {
    assert.match(aiJob, /pushResultPromise\.then\(\(\) => finishPush\(\), error => finishPush\(/);
    assert.match(aiJob, /BOSS 页面可能已改版，请刷新页面后重试/);
});
