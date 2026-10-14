import assert from 'node:assert/strict';
import test from 'node:test';
import * as pendingConversation from '../src/platform/pendingConversation.ts';
import {shouldTreatPendingConversationAsReplyable} from '../src/platform/pendingConversation.ts';

test('candidate-last-message wins over an unread marker after reconnect', () => {
    assert.equal(shouldTreatPendingConversationAsReplyable({
        unread: true,
        preview: '你好，方便沟通吗？',
        lastMessageSenderId: 100,
        currentUserId: 100,
        selected: false,
        latestSender: 'unknown',
    }), false);
});

test('unread HR preview remains replyable after reconnect', () => {
    assert.equal(shouldTreatPendingConversationAsReplyable({
        unread: true,
        preview: '你好，方便沟通吗？',
        lastMessageSenderId: 200,
        currentUserId: 100,
        selected: false,
        latestSender: 'unknown',
    }), true);
});

test('unselected recovery only trusts sender metadata matching the target HR', () => {
    const input = {
        unread: true,
        preview: '你好，方便沟通吗？',
        lastMessageSenderId: 200,
        currentUserId: 100,
        selected: false,
        latestSender: 'unknown' as const,
        expectedSenderId: 200,
    };

    assert.equal(shouldTreatPendingConversationAsReplyable(input), true);
    assert.equal(shouldTreatPendingConversationAsReplyable({
        ...input,
        expectedSenderId: 300,
    }), false);
});

test('selected conversation can use the visible latest HR message when metadata is missing', () => {
    assert.equal(shouldTreatPendingConversationAsReplyable({
        unread: false,
        preview: '你好',
        lastMessageSenderId: null,
        currentUserId: 100,
        selected: true,
        latestSender: 'boss',
    }), true);
});

test('a selected conversation latest candidate message overrides a stale unread marker', () => {
    assert.equal(shouldTreatPendingConversationAsReplyable({
        unread: true,
        preview: '旧的 HR 消息',
        lastMessageSenderId: null,
        currentUserId: 100,
        selected: true,
        latestSender: 'candidate',
    }), false);
});

test('a selected conversation with unknown latest sender is not guessed from an unread marker', () => {
    assert.equal(shouldTreatPendingConversationAsReplyable({
        unread: true,
        preview: '旧的 HR 消息',
        lastMessageSenderId: null,
        currentUserId: 100,
        selected: true,
        latestSender: 'unknown',
        latestMessageObserved: true,
    }), false);
});

test('unselected unread contact with unknown sender is not assumed to need a reply', () => {
    assert.equal(shouldTreatPendingConversationAsReplyable({
        unread: true,
        preview: '旧的 HR 消息',
        lastMessageSenderId: null,
        currentUserId: 100,
        selected: false,
        latestSender: 'unknown',
    }), false);
});

test('selected visible HR message overrides stale candidate sender metadata', () => {
    assert.equal(shouldTreatPendingConversationAsReplyable({
        unread: false,
        preview: 'HR 的最新消息',
        lastMessageSenderId: 100,
        currentUserId: 100,
        selected: true,
        latestSender: 'boss',
        latestMessageObserved: true,
    }), true);
});

test('selected visible candidate message overrides stale HR sender metadata', () => {
    assert.equal(shouldTreatPendingConversationAsReplyable({
        unread: true,
        preview: '候选人的最新消息',
        lastMessageSenderId: 200,
        currentUserId: 100,
        selected: true,
        latestSender: 'candidate',
        latestMessageObserved: true,
    }), false);
});

test('selected visible message with unknown sender does not fall back to stale list metadata', () => {
    assert.equal(shouldTreatPendingConversationAsReplyable({
        unread: true,
        preview: '最新消息',
        lastMessageSenderId: 200,
        currentUserId: 100,
        selected: true,
        latestSender: 'unknown',
        latestMessageObserved: true,
    }), false);
});

test('visible message must belong to the active target HR before recovery', () => {
    const verifyIdentity = typeof pendingConversation.isPendingConversationIdentityVerified === 'function'
        ? pendingConversation.isPendingConversationIdentityVerified
        : () => false;

    assert.equal(verifyIdentity(200, 200, 200), true);
    assert.equal(verifyIdentity(200, 100, 100), false);
    assert.equal(verifyIdentity(200, 200, 100), false);
    assert.equal(verifyIdentity(200, 200, null), false);
});
