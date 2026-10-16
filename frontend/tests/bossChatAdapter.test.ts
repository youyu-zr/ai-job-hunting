import assert from 'node:assert/strict';
import test from 'node:test';
import {
    ensureReadyBossChatChannel,
    hasReadyBossChatChannel,
    isBossMessageSenderReady,
    sendBossChatMessage,
    sendBossChatMessageAsync,
} from '../src/webSocket/bossChatAdapter.ts';
import {isDeliveryAccepted} from '../src/webSocket/deliveryOutcome.ts';

test('initializes the BOSS socket before sending when the page has a ready worker but is disconnected', async () => {
    let initCalls = 0;
    let workerSendCalls = 0;
    let status = 'DISCONNECTED';
    const worker = {
        isReady: false,
        sendTextMessage: async () => {
            workerSendCalls += 1;
        },
    };
    const socketConnect = {
        init: async () => {
            initCalls += 1;
            status = 'CONNECTED';
            worker.isReady = true;
        },
        socketStrategy: {
            initSocket: async () => {
            initCalls += 1;
            status = 'CONNECTED';
            worker.isReady = true;
            },
            getSharedWorkerClient: () => worker,
            broadcastManager: {
                strategy: {
                    get socketStatus() {
                        return status;
                    },
                    sharedWorkerClient: worker,
                },
            },
        },
        sendTextMessage: () => null,
    };

    const win = {GeekChatCore: {getInstance: () => ({socketConnect})}};
    assert.equal(await ensureReadyBossChatChannel(win, 100, 1), true);
    assert.equal(initCalls, 1);
    assert.equal(workerSendCalls, 0);
});

test('uses the underlying SharedWorker promise because the SDK wrapper returns null', async () => {
    let wrapperCalls = 0;
    let workerCalls = 0;
    const worker = {
        isReady: true,
        sendTextMessage: async () => {
            workerCalls += 1;
            return 'worker-accepted';
        },
    };
    const socketConnect = {
        socketStrategy: {
            getSharedWorkerClient: () => worker,
            broadcastManager: {
                strategy: {
                    socketStatus: 'CONNECTED',
                    sharedWorkerClient: worker,
                },
            },
        },
        sendTextMessage: () => {
            wrapperCalls += 1;
            return null;
        },
    };

    const result = await sendBossChatMessageAsync(
        {GeekChatCore: {getInstance: () => ({socketConnect})}},
        {uid: 42, encryptUid: 'encrypted-boss', friendSource: 7},
        '岗位相关的招呼语',
        'text',
        () => false,
        {officialOnly: true},
    );

    assert.deepEqual(result, {
        sent: true,
        confirmed: true,
        channel: 'geek-chat-core',
    });
    assert.equal(workerCalls, 1);
    assert.equal(wrapperCalls, 0);
});

test('uses the shared worker from a non-chat page even when that page status is stale', async () => {
    let workerCalls = 0;
    let initCalls = 0;
    const worker = {
        isReady: true,
        sendTextMessage: async () => {
            workerCalls += 1;
            return 'worker-accepted';
        },
    };
    const socketConnect = {
        init: () => {
            initCalls += 1;
        },
        socketStrategy: {
            getSharedWorkerClient: () => worker,
            broadcastManager: {
                strategy: {
                    socketStatus: 'DISCONNECTED',
                    sharedWorkerClient: worker,
                },
            },
        },
        sendTextMessage: () => null,
    };

    assert.equal(hasReadyBossChatChannel({GeekChatCore: {getInstance: () => ({socketConnect})}}), true);
    assert.equal(await ensureReadyBossChatChannel({GeekChatCore: {getInstance: () => ({socketConnect})}}, 20, 1), true);
    const result = await sendBossChatMessageAsync(
        {GeekChatCore: {getInstance: () => ({socketConnect})}},
        {uid: 42, encryptUid: 'encrypted-boss'},
        '跨页面会话招呼语',
        'text',
        () => false,
        {officialOnly: true},
    );

    assert.equal(result.sent, true);
    assert.equal(result.confirmed, true);
    assert.equal(workerCalls, 1);
    assert.equal(initCalls, 1);
});

test('requires the official connected websocket before the delivery flow starts', () => {
    assert.equal(hasReadyBossChatChannel({
        GeekChatCore: {
            getInstance: () => ({socketConnect: {sendTextMessage: () => undefined}}),
        },
    }), false);
    assert.equal(hasReadyBossChatChannel({
        ChatWebsocketImage: {
            sendText: () => undefined,
            client: {isConnected: () => false},
        },
    }), false);
    assert.equal(hasReadyBossChatChannel({
        ChatWebsocketImage: {
            sendText: () => undefined,
            client: {isConnected: () => true},
        },
    }), true);
});

test('recognizes the current BOSS GeekChatCore SharedWorker channel as ready', () => {
    assert.equal(hasReadyBossChatChannel({
        GeekChatCore: {
            getInstance: () => ({
                socketConnect: {
                    sendTextMessage: () => undefined,
                    socketStrategy: {
                        broadcastManager: {
                            strategy: {
                                socketStatus: 'CONNECTED',
                                sharedWorkerClient: {isReady: true},
                            },
                        },
                    },
                },
            }),
        },
    }), true);
});

test('recognizes the GeekChatCore client sender used by the previous working delivery path', () => {
    assert.equal(hasReadyBossChatChannel({
        GeekChatCore: {
            getInstance: () => ({
                getClient: () => ({client: {send: () => undefined}}),
            }),
        },
    }), true);
});

test('preserves the legacy GeekChatCore client acknowledgement instead of swallowing it', async () => {
    let clientCalls = 0;
    const result = await sendBossChatMessageAsync(
        {
            GeekChatCore: {
                getInstance: () => ({
                    getClient: () => ({
                        client: {
                            send: () => {
                                clientCalls += 1;
                                return Promise.resolve('server-accepted');
                            },
                        },
                    }),
                }),
            },
        },
        {uid: 42, encryptUid: 'encrypted-boss'},
        '岗位相关的招呼语',
        'text',
        () => Promise.resolve('server-accepted'),
        {officialOnly: true},
    );

    assert.equal(clientCalls, 0);
    assert.deepEqual(result, {
        sent: true,
        confirmed: true,
        channel: 'geek-chat-core',
    });
});

test('uses the current BOSS GeekChatCore SharedWorker channel for official-only text delivery', async () => {
    const calls: unknown[][] = [];
    const result = await sendBossChatMessageAsync(
        {
            GeekChatCore: {
                getInstance: () => ({
                    socketConnect: {
                        sendTextMessage: (...args: unknown[]) => {
                            calls.push(args);
                            return null;
                        },
                        socketStrategy: {
                            broadcastManager: {
                                strategy: {
                                    socketStatus: 'CONNECTED',
                                    sharedWorkerClient: {isReady: true},
                                },
                            },
                        },
                    },
                }),
            },
        },
        {uid: 42, encryptUid: 'encrypted-boss', friendSource: 7},
        '岗位相关的招呼语',
        'text',
        () => {
            throw new Error('legacy sender must not be used');
        },
        {officialOnly: true},
    );

    assert.deepEqual(result, {
        sent: true,
        confirmed: false,
        channel: 'geek-chat-core',
    });
    assert.deepEqual(calls, [[
        {
            uid: 42,
            friendSource: 7,
            source: 7,
            encryptUid: 'encrypted-boss',
            securityId: undefined,
            encryptJobId: undefined,
            jobTitle: undefined,
        },
        '岗位相关的招呼语',
    ]]);
});

test('does not treat an initialized-looking official sender as ready before its socket connects', () => {
    assert.equal(isBossMessageSenderReady({
        send: () => undefined,
        sendText: () => undefined,
    }), false);
    assert.equal(isBossMessageSenderReady({
        send: () => undefined,
        sendText: () => undefined,
        client: {isConnected: () => false},
    }), false);
    assert.equal(isBossMessageSenderReady({
        send: () => undefined,
        sendText: () => undefined,
        client: {isConnected: () => true},
    }), true);
});

test('uses the current GeekChatCore text channel without invoking the legacy sender', () => {
    let sendMessageCalls: unknown[][] = [];
    let legacyCalls = 0;
    const sendMessage = (...args: unknown[]) => sendMessageCalls.push(args);
    const legacySend = () => {
        legacyCalls += 1;
        return true;
    };
    const win = {
        GeekChatCore: {
            getInstance: () => ({socketConnect: {sendMessage}}),
        },
    };

    const result = sendBossChatMessage(
        win,
        {uid: '42', encryptUid: 'encrypted-boss', friendSource: 0},
        '岗位相关的招呼语',
        'text',
        legacySend,
    );

    assert.deepEqual(result, {sent: true, channel: 'geek-chat-core'});
    assert.equal(sendMessageCalls.length, 1);
    assert.deepEqual(sendMessageCalls[0]?.[0], {
        uid: 42,
        friendSource: 0,
        source: 0,
        encryptUid: 'encrypted-boss',
        securityId: undefined,
        encryptJobId: undefined,
        jobTitle: undefined,
    });
    assert.deepEqual(sendMessageCalls[0]?.slice(1), ['岗位相关的招呼语', 'text']);
    assert.equal(legacyCalls, 0);
});

test('falls back to the legacy sender only when the current channel is unavailable', () => {
    let legacyCalls = 0;
    const legacySend = () => {
        legacyCalls += 1;
        return true;
    };

    const result = sendBossChatMessage(
        {},
        {uid: 42, encryptUid: 'encrypted-boss'},
        '岗位相关的招呼语',
        'text',
        legacySend,
    );

    assert.deepEqual(result, {sent: true, channel: 'legacy'});
    assert.equal(legacyCalls, 1);
});

test('does not fall back after a successful current-channel call', () => {
    let sendMessageCalls = 0;
    let legacyCalls = 0;
    const sendMessage = () => {
        sendMessageCalls += 1;
    };
    const legacySend = () => {
        legacyCalls += 1;
        return true;
    };
    const win = {
        GeekChatCore: {
            getInstance: () => ({socketConnect: {sendMessage}}),
        },
    };

    sendBossChatMessage(win, {uid: 42, encryptUid: 'encrypted-boss'}, '只发一次', 'text', legacySend);

    assert.equal(sendMessageCalls, 1);
    assert.equal(legacyCalls, 0);
});

test('uses the official ChatWebsocket sendText path for text and waits for its acknowledgement', async () => {
    const calls: unknown[] = [];
    let legacyCalls = 0;
    const sendText = (params: unknown) => {
        calls.push(params);
        return Promise.resolve('server-message-sync');
    };
    const result = await sendBossChatMessageAsync(
        {
            ChatWebsocketImage: {
                sendText,
                client: {isConnected: () => true},
            },
        },
        {
            uid: '42',
            encryptUid: 'encrypted-boss',
            friendSource: 7,
        },
        '针对岗位的首句',
        'text',
        () => {
            legacyCalls += 1;
            return true;
        },
    );

    assert.deepEqual(result, {
        sent: true,
        confirmed: true,
        channel: 'official-chatwebsocket',
    });
    assert.deepEqual(calls, [{
        uid: 42,
        source: 7,
        encryptUid: 'encrypted-boss',
        message: '针对岗位的首句',
    }]);
    assert.equal(legacyCalls, 0);
});

test('does not use official sendText while its websocket is disconnected', async () => {
    let officialCalls = 0;
    let currentCalls = 0;
    const result = await sendBossChatMessageAsync(
        {
            ChatWebsocketImage: {
                sendText: () => {
                    officialCalls += 1;
                    return Promise.resolve();
                },
                client: {isConnected: () => false},
            },
            GeekChatCore: {
                getInstance: () => ({
                    socketConnect: {
                        sendTextMessage: () => {
                            currentCalls += 1;
                            return Promise.resolve();
                        },
                    },
                }),
            },
        },
        {uid: 42, encryptUid: 'encrypted-boss'},
        '等待连接后发送',
        'text',
        () => false,
    );

    assert.equal(officialCalls, 0);
    assert.equal(currentCalls, 1);
    assert.deepEqual(result, {
        sent: true,
        confirmed: true,
        channel: 'geek-chat-core',
    });
});

test('treats a broken official connection probe as unavailable', async () => {
    let officialCalls = 0;
    let legacyCalls = 0;
    const result = await sendBossChatMessageAsync(
        {
            ChatWebsocketImage: {
                sendText: () => {
                    officialCalls += 1;
                    return Promise.resolve();
                },
                client: {
                    isConnected: () => {
                        throw new Error('stale websocket client');
                    },
                },
            },
        },
        {uid: 42, encryptUid: 'encrypted-boss'},
        '连接探测异常',
        'text',
        () => {
            legacyCalls += 1;
            return true;
        },
    );

    assert.equal(officialCalls, 0);
    assert.equal(legacyCalls, 1);
    assert.deepEqual(result, {
        sent: true,
        confirmed: false,
        channel: 'legacy',
    });
});

test('does not claim confirmation when a compatible sender returns without an acknowledgement', async () => {
    let legacyCalls = 0;
    const result = await sendBossChatMessageAsync(
        {
            GeekChatCore: {
                getInstance: () => ({
                    socketConnect: {
                        sendTextMessage: () => undefined,
                    },
                }),
            },
        },
        {uid: 42, encryptUid: 'encrypted-boss'},
        '等待回执',
        'text',
        () => {
            legacyCalls += 1;
            return true;
        },
    );

    assert.deepEqual(result, {
        sent: true,
        confirmed: false,
        channel: 'geek-chat-core',
    });
    assert.equal(legacyCalls, 0);
});

test('does not fall through to another sender after official sendText rejects', async () => {
    let legacyCalls = 0;
    const result = await sendBossChatMessageAsync(
        {
            ChatWebsocketImage: {
                sendText: () => Promise.reject(new Error('socket unavailable')),
                client: {isConnected: () => true},
            },
        },
        {uid: 42, encryptUid: 'encrypted-boss'},
        '不能重复发送',
        'text',
        () => {
            legacyCalls += 1;
            return true;
        },
    );

    assert.equal(result.sent, false);
    assert.equal(result.confirmed, false);
    assert.equal(result.channel, 'official-chatwebsocket');
    assert.equal(legacyCalls, 0);
});

test('official-only delivery does not fall back to a compatibility sender', async () => {
    let legacyCalls = 0;
    const result = await sendBossChatMessageAsync(
        {},
        {uid: 42, encryptUid: 'encrypted-boss'},
        '只允许官方通道发送',
        'text',
        () => {
            legacyCalls += 1;
            return true;
        },
        {officialOnly: true},
    );

    assert.equal(result.sent, false);
    assert.equal(result.channel, 'none');
    assert.equal(legacyCalls, 0);
});

test('official-only delivery can use the BOSS GeekChatCore client when websocket objects are not exposed', async () => {
    let clientCalls = 0;
    const result = await sendBossChatMessageAsync(
        {
            GeekChatCore: {
                getInstance: () => ({
                    getClient: () => ({client: {send: () => {
                        clientCalls += 1;
                    }}}),
                }),
            },
        },
        {uid: 42, encryptUid: 'encrypted-boss'},
        '岗位化招呼语',
        'text',
        () => {
            clientCalls += 1;
            return true;
        },
        {officialOnly: true, allowCompatibilityFallback: true},
    );

    assert.deepEqual(result, {
        sent: true,
        confirmed: false,
        channel: 'geek-chat-core',
    });
    assert.equal(clientCalls, 1);
});

test('treats a successful legacy client invocation with no return value as submitted', async () => {
    let clientCalls = 0;
    const result = await sendBossChatMessageAsync(
        {GeekChatCore: {getInstance: () => ({getClient: () => ({client: {send: () => undefined}})})}},
        {uid: 42, encryptUid: 'encrypted-boss'},
        '等待己方消息回显',
        'text',
        () => {
            clientCalls += 1;
            return true;
        },
        {officialOnly: true, allowCompatibilityFallback: true},
    );

    assert.equal(clientCalls, 1);
    assert.deepEqual(result, {
        sent: true,
        confirmed: false,
        channel: 'geek-chat-core',
    });
});

test('treats a platform-accepted send as delivered even when the page has no own-message echo', async () => {
    const result = await sendBossChatMessageAsync(
        {
            ChatWebsocketImage: {
                sendText: () => undefined,
                client: {isConnected: () => true},
            },
        },
        {uid: 42, encryptUid: 'encrypted-boss'},
        '普通聊天回复',
        'text',
        () => false,
    );

    assert.deepEqual(result, {
        sent: true,
        confirmed: false,
        channel: 'official-chatwebsocket',
    });
});

test('does not block the AI reply flow on a missing own-message echo', () => {
    assert.equal(isDeliveryAccepted({sent: true, confirmed: false}), true);
    assert.equal(isDeliveryAccepted({sent: false, confirmed: false}), false);
});
