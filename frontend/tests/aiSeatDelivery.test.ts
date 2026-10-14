import assert from 'node:assert/strict';
import test from 'node:test';
import {
    canScanUnreadConversations,
    canDeliverAiSeatTurn,
    parseAiSeatRuntimeStatus,
} from '../src/platform/aiSeatDelivery.ts';
import {ConversationPauseRegistry} from '../src/platform/pauseRegistry.ts';

test('an AI-seat reply is cancelled if the global switch turns off during generation', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'seat-off-during-generation';
    const turnGeneration = ConversationPauseRegistry.beginTurn(jobKey);

    assert.equal(canDeliverAiSeatTurn({
        aiSeatEnabled: false,
        turnSeatRevision: 1,
        currentSeatRevision: 2,
        jobKey,
        turnGeneration,
        serverStatusFresh: true,
        serverSeatEnabled: false,
        serverSessionPaused: false,
        serverGatePaused: false,
        turnServerRevision: 1,
        currentServerRevision: 2,
        turnSessionRevision: 1,
        currentSessionRevision: 1,
    }), false);
});

test('turning the global switch off and back on still invalidates an older reply', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'seat-reenabled-during-generation';
    const turnGeneration = ConversationPauseRegistry.beginTurn(jobKey);

    assert.equal(canDeliverAiSeatTurn({
        aiSeatEnabled: true,
        turnSeatRevision: 4,
        currentSeatRevision: 6,
        jobKey,
        turnGeneration,
        serverStatusFresh: true,
        serverSeatEnabled: true,
        serverSessionPaused: false,
        serverGatePaused: false,
        turnServerRevision: 4,
        currentServerRevision: 4,
        turnSessionRevision: 4,
        currentSessionRevision: 4,
    }), false);
});

test('an unchanged enabled seat and active conversation can deliver its reply', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'active-seat';
    const turnGeneration = ConversationPauseRegistry.beginTurn(jobKey);

    assert.equal(canDeliverAiSeatTurn({
        aiSeatEnabled: true,
        turnSeatRevision: 3,
        currentSeatRevision: 3,
        jobKey,
        turnGeneration,
        serverStatusFresh: true,
        serverSeatEnabled: true,
        serverSessionPaused: false,
        serverGatePaused: false,
        turnServerRevision: 3,
        currentServerRevision: 3,
        turnSessionRevision: 3,
        currentSessionRevision: 3,
    }), true);
});

test('a cross-device seat revision invalidates a turn even if the seat is open again', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'cross-device-seat-reenabled';
    const turnGeneration = ConversationPauseRegistry.beginTurn(jobKey);

    assert.equal(canDeliverAiSeatTurn({
        aiSeatEnabled: true,
        turnSeatRevision: 5,
        currentSeatRevision: 5,
        jobKey,
        turnGeneration,
        serverStatusFresh: true,
        serverSeatEnabled: true,
        serverSessionPaused: false,
        serverGatePaused: false,
        turnServerRevision: 100,
        currentServerRevision: 102,
        turnSessionRevision: 100,
        currentSessionRevision: 100,
    }), false);
});

test('unknown or stale server status fails closed before delivery', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'stale-server-status';
    const turnGeneration = ConversationPauseRegistry.beginTurn(jobKey);

    assert.equal(canDeliverAiSeatTurn({
        aiSeatEnabled: true,
        turnSeatRevision: 1,
        currentSeatRevision: 1,
        jobKey,
        turnGeneration,
        serverStatusFresh: false,
        serverSeatEnabled: true,
        serverSessionPaused: false,
        serverGatePaused: false,
        turnServerRevision: 10,
        currentServerRevision: 10,
        turnSessionRevision: 10,
        currentSessionRevision: 10,
    }), false);
});

test('a server-side manual session pause blocks delivery on another device', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'server-session-paused';
    const turnGeneration = ConversationPauseRegistry.beginTurn(jobKey);

    assert.equal(canDeliverAiSeatTurn({
        aiSeatEnabled: true,
        turnSeatRevision: 1,
        currentSeatRevision: 1,
        jobKey,
        turnGeneration,
        serverStatusFresh: true,
        serverSeatEnabled: true,
        serverSessionPaused: true,
        serverGatePaused: false,
        turnServerRevision: 7,
        currentServerRevision: 7,
        turnSessionRevision: 7,
        currentSessionRevision: 7,
    }), false);
});

test('a cross-device pause and resume revision invalidates the original turn', () => {
    ConversationPauseRegistry.reset();
    const jobKey = 'cross-device-session-reenabled';
    const turnGeneration = ConversationPauseRegistry.beginTurn(jobKey);

    assert.equal(canDeliverAiSeatTurn({
        aiSeatEnabled: true,
        turnSeatRevision: 5,
        currentSeatRevision: 5,
        jobKey,
        turnGeneration,
        serverStatusFresh: true,
        serverSeatEnabled: true,
        serverSessionPaused: false,
        serverGatePaused: false,
        turnServerRevision: 100,
        currentServerRevision: 100,
        turnSessionRevision: 10,
        currentSessionRevision: 12,
    }), false);
});

test('runtime status requires authoritative booleans and monotonic revisions', () => {
    assert.deepEqual(parseAiSeatRuntimeStatus({
        globalEnabled: true,
        manuallyPaused: false,
        gatePaused: false,
        globalRevision: 12,
        sessionRevision: 4,
    }), {
        globalEnabled: true,
        manuallyPaused: false,
        gatePaused: false,
        globalRevision: 12,
        sessionRevision: 4,
    });
    assert.equal(parseAiSeatRuntimeStatus({
        globalEnabled: 'true',
        manuallyPaused: false,
        gatePaused: false,
        globalRevision: 12,
        sessionRevision: 4,
    }), null);
    assert.equal(parseAiSeatRuntimeStatus({
        globalEnabled: true,
        manuallyPaused: false,
        gatePaused: false,
        globalRevision: 12,
    }), null);
});

test('unread recovery only scans contacts after authoritative account status confirms the seat is open', async () => {
    assert.equal(canScanUnreadConversations(true), true);
    assert.equal(canScanUnreadConversations(false), false);
    assert.equal(canScanUnreadConversations(null), false);
});
