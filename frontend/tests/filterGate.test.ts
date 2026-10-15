import assert from 'node:assert/strict';
import test from 'node:test';
import {
    shouldBlockAfterAiFilter,
    shouldRetryAfterAiFilter,
} from '../src/platform/filterGate.ts';

test('DELIVER proceeds through the delivery gate', () => {
    assert.equal(shouldBlockAfterAiFilter({
        filter: false,
        recommendedAction: 'DELIVER',
        riskLevel: 'LOW',
    }), false);
});

test('FILTER skips delivery through the delivery gate', () => {
    assert.equal(shouldBlockAfterAiFilter({
        filter: true,
        recommendedAction: 'FILTER',
        riskLevel: 'HIGH',
    }), true);
});

test('PROCESSING_ERROR skips delivery but remains retryable for the user', () => {
    const result = {
        filter: false,
        recommendedAction: 'PROCESSING_ERROR',
        riskLevel: 'MEDIUM',
        reason: '岗位分析暂时不可用，请稍后重试',
    };
    assert.equal(shouldBlockAfterAiFilter(result), true);
    assert.equal(shouldRetryAfterAiFilter(result), true);
});

test('malformed or unsupported AI decisions are blocked as retryable failures', () => {
    for (const result of [
        { recommendedAction: 'UNKNOWN', riskLevel: 'LOW', filter: false },
        { recommendedAction: 'CHAT_FIRST', riskLevel: 'LOW', filter: false },
        { recommendedAction: 'HUMAN_REVIEW', riskLevel: 'MEDIUM', filter: false },
        { recommendedAction: 'DELIVER', riskLevel: 'MAYBE', filter: false },
        { recommendedAction: 'DELIVER', riskLevel: 'LOW' },
    ]) {
        assert.equal(shouldBlockAfterAiFilter(result), true);
        assert.equal(shouldRetryAfterAiFilter(result), true);
    }
});

test('low-risk incomplete results are not blocked only because clarification is needed', () => {
    assert.equal(shouldBlockAfterAiFilter({
        filter: false,
        recommendedAction: 'DELIVER',
        riskLevel: 'LOW',
        clarificationQuestions: ['工作方式是否支持远程协作？'],
    }), false);
});
