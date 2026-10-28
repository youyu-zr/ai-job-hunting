import assert from 'node:assert/strict';
import test from 'node:test';
import {shouldCompleteStoppedReply} from '../src/platform/replyDisposition.ts';

test('unknown STOP responses remain retryable', () => {
    assert.equal(shouldCompleteStoppedReply({}), false);
    assert.equal(shouldCompleteStoppedReply(null), false);
    assert.equal(shouldCompleteStoppedReply({stopReason: 'NO_REPLY_CONTENT'}), false);
});

test('a duplicate or still-generating reply is never treated as finished', () => {
    // 旧后端超时后会回 DUPLICATE_MESSAGE；当成完成会让这条 HR 消息永远不回。
    assert.equal(shouldCompleteStoppedReply({stopReason: 'DUPLICATE_MESSAGE'}), false);
    assert.equal(shouldCompleteStoppedReply({stopReason: 'IN_PROGRESS', retryable: true}), false);
    assert.equal(shouldCompleteStoppedReply({stopReason: 'IN_PROGRESS'}), false);
});

test('explicit terminal STOP responses can be completed', () => {
    assert.equal(shouldCompleteStoppedReply({stopReason: 'SESSION_PAUSED'}), true);
    assert.equal(shouldCompleteStoppedReply({stopReason: 'SILENT_SYSTEM'}), true);
    assert.equal(shouldCompleteStoppedReply({stopReason: 'MARKETING_SILENT'}), true);
    assert.equal(shouldCompleteStoppedReply({stopReason: 'PROMOTION_SILENT'}), true);
    assert.equal(shouldCompleteStoppedReply({stopReason: 'HIGH_RISK_JOB'}), true);
    assert.equal(shouldCompleteStoppedReply({stopReason: 'USER_CONFIRMATION_REQUIRED'}), true);
    assert.equal(shouldCompleteStoppedReply({
        stopReason: 'AI_PROCESSING_ERROR',
        retryable: true,
    }), false);
    assert.equal(shouldCompleteStoppedReply({jobFilterDecision: {
        filter: true,
        recommendedAction: 'FILTER',
        riskLevel: 'HIGH',
    }}), true);
});

test('IN_PROGRESS is handled quietly before the error notification', async () => {
    const {readFileSync} = await import('node:fs');
    const source = readFileSync(new URL('../src/platform/bossPlatform.ts', import.meta.url), 'utf8');
    const inProgress = source.indexOf("stopReason === 'IN_PROGRESS'");
    const errorBranch = source.indexOf("stopReason === 'AI_PROCESSING_ERROR'");
    assert.ok(inProgress > 0 && inProgress < errorBranch);
    const branch = source.slice(inProgress, errorBranch);
    assert.match(branch, /removeBatchMessages\(batch\)/);
    assert.doesNotMatch(branch, /ElNotification|markBatchMessagesAsCompleted/);
});
