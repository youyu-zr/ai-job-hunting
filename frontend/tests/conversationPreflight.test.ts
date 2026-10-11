import assert from 'node:assert/strict';
import test from 'node:test';
import {
    shouldBlockAfterAiFilter,
    shouldBlockConversationPreflight,
} from '../src/platform/filterGate.ts';

test('does not stop chat when the optional client screening result is missing', () => {
    assert.equal(shouldBlockConversationPreflight(null), false);
    assert.equal(shouldBlockConversationPreflight(undefined), false);
});

test('stops chat early only when the client screening result explicitly blocks it', () => {
    assert.equal(shouldBlockConversationPreflight({
        filter: true,
        recommendedAction: 'FILTER',
        riskLevel: 'HIGH',
    }), true);
    assert.equal(shouldBlockConversationPreflight({
        filter: false,
        recommendedAction: 'CHAT_FIRST',
        riskLevel: 'MEDIUM',
    }), false);
    assert.equal(shouldBlockConversationPreflight({
        filter: false,
        recommendedAction: 'HUMAN_REVIEW',
        riskLevel: 'MEDIUM',
    }), false);
});

test('does not block delivery for an ambiguous low-risk job that should be clarified in chat', () => {
    assert.equal(shouldBlockAfterAiFilter({
        filter: false,
        recommendedAction: 'HUMAN_REVIEW',
        riskLevel: 'MEDIUM',
    }), false);
});
