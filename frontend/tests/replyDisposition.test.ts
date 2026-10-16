import assert from 'node:assert/strict';
import test from 'node:test';
import {shouldCompleteStoppedReply} from '../src/platform/replyDisposition.ts';

test('unknown STOP responses remain retryable', () => {
    assert.equal(shouldCompleteStoppedReply({}), false);
    assert.equal(shouldCompleteStoppedReply(null), false);
    assert.equal(shouldCompleteStoppedReply({stopReason: 'NO_REPLY_CONTENT'}), false);
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
