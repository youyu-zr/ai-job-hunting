import assert from 'node:assert/strict';
import test from 'node:test';
import {shouldDelegateConversationTextToAi} from '../src/platform/conversationInput.ts';

test('delegates contact markup to AI instead of filtering by a fixed token', () => {
    assert.equal(shouldDelegateConversationTextToAi('方便加我&lt;/phone&gt;吗？'), true);
    assert.equal(shouldDelegateConversationTextToAi('可以加微信&lt;/copy&gt;沟通'), true);
});

test('delegates rejection and resume requests so AI can decide from context', () => {
    assert.equal(shouldDelegateConversationTextToAi('对方拒绝了您的发送请求'), true);
    assert.equal(shouldDelegateConversationTextToAi('方便发一份简历过来吗？'), true);
});

test('does not delegate empty message text', () => {
    assert.equal(shouldDelegateConversationTextToAi('   '), false);
});
