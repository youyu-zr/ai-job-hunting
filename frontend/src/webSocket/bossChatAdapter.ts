export type BossChatUserLike = {
    uid: string | number;
    encryptUid?: string;
    encryptBossId?: string;
    friendSource?: string | number;
    source?: string | number;
    securityId?: string;
    encryptJobId?: string | number;
    jobTitle?: string;
};

type BossChatWindow = {
    ChatWebsocket?: OfficialChatWebsocket;
    ChatWebsocketImage?: OfficialChatWebsocket;
    GeekChatCore?: {
        getInstance?: () => {
            socketConnect?: {
                init?: () => unknown;
                sendMessage?: (user: Record<string, unknown>, payload: unknown, type: 'text' | 'image') => unknown;
                sendTextMessage?: (user: Record<string, unknown>, text: string) => unknown;
                socketStrategy?: SocketStrategy;
            };
            getClient?: () => {
                client?: {
                    send?: (message: unknown) => unknown;
                };
            };
        };
    };
};

type LegacySender = () => unknown;
type OfficialChatWebsocket = {
    sendText?: (params: {
        uid: number;
        source: number;
        encryptUid: string;
        message: string;
    }) => unknown;
    client?: {
        isConnected?: () => boolean;
    };
};
type SocketConnect = {
    init?: () => unknown;
    sendMessage?: (user: Record<string, unknown>, payload: unknown, type: 'text' | 'image') => unknown;
    sendTextMessage?: (user: Record<string, unknown>, text: string) => unknown;
    socketStrategy?: SocketStrategy;
};

type SocketStrategy = {
    initSocket?: () => unknown;
    getSharedWorkerClient?: () => SharedWorkerClientLike | undefined;
    broadcastManager?: {
        strategy?: {
            socketStatus?: string;
            sharedWorkerClient?: {
                isReady?: boolean;
            };
        };
    };
};

type SharedWorkerClientLike = {
    isReady?: boolean;
    sendTextMessage?: (user: Record<string, unknown>, text: string) => unknown;
    sendMessage?: (type: string, payload: unknown) => unknown;
};

export type BossChatSendResult = {
    sent: boolean;
    confirmed: boolean;
    channel: 'official-chatwebsocket' | 'geek-chat-core' | 'legacy' | 'none';
    error?: unknown;
};

export type BossChatSendOptions = {
    officialOnly?: boolean;
    allowCompatibilityFallback?: boolean;
};

function normalizeUser(userLike: BossChatUserLike): Record<string, unknown> {
    const uid = Number(userLike.uid);
    const encryptUid = String(userLike.encryptUid || userLike.encryptBossId || '');
    if (!Number.isFinite(uid) || uid <= 0 || !encryptUid) {
        throw new Error('BOSS 联系人数据不完整');
    }
    const friendSource = Number(userLike.friendSource ?? userLike.source ?? 0);
    return {
        uid,
        friendSource,
        source: friendSource,
        encryptUid,
        securityId: userLike.securityId,
        encryptJobId: userLike.encryptJobId === undefined ? undefined : String(userLike.encryptJobId),
        jobTitle: userLike.jobTitle,
    };
}

function getSocketConnect(win: BossChatWindow): SocketConnect | undefined {
    try {
        return win.GeekChatCore?.getInstance?.()?.socketConnect;
    } catch {
        return undefined;
    }
}

function hasLegacyGeekChatClient(win: BossChatWindow): boolean {
    try {
        return typeof win.GeekChatCore?.getInstance?.()?.getClient?.()?.client?.send === 'function';
    } catch {
        return false;
    }
}

function getOfficialChatWebsocket(win: BossChatWindow): OfficialChatWebsocket | undefined {
    const candidates = [win.ChatWebsocket, win.ChatWebsocketImage];
    return candidates.find(candidate => {
        if (typeof candidate?.sendText !== 'function' || typeof candidate.client?.isConnected !== 'function') {
            return false;
        }
        try {
            return candidate.client.isConnected();
        } catch {
            return false;
        }
    });
}

function isReadyGeekChatCore(socketConnect: SocketConnect | undefined): boolean {
    if (!socketConnect || (typeof socketConnect.sendTextMessage !== 'function' && typeof socketConnect.sendMessage !== 'function')) {
        return false;
    }
    const sharedWorkerClient = getSharedWorkerClient(socketConnect);
    if (sharedWorkerClient?.isReady === true && typeof sharedWorkerClient.sendTextMessage === 'function') {
        return true;
    }
    const strategy = socketConnect.socketStrategy?.broadcastManager?.strategy;
    if (!strategy || strategy.socketStatus !== 'CONNECTED') {
        return false;
    }
    return strategy.sharedWorkerClient?.isReady === true;
}

function isSocketConnected(socketConnect: SocketConnect | undefined): boolean {
    return socketConnect?.socketStrategy?.broadcastManager?.strategy?.socketStatus === 'CONNECTED';
}

function getSharedWorkerClient(socketConnect: SocketConnect | undefined): SharedWorkerClientLike | undefined {
    try {
        return socketConnect?.socketStrategy?.getSharedWorkerClient?.()
            || socketConnect?.socketStrategy?.broadcastManager?.strategy?.sharedWorkerClient;
    } catch {
        return undefined;
    }
}

async function initializeGeekChatCore(socketConnect: SocketConnect | undefined): Promise<void> {
    if (!socketConnect) {
        return;
    }
    if (typeof socketConnect.init === 'function') {
        const result = socketConnect.init();
        if (isThenable(result)) {
            void Promise.resolve(result).catch(() => undefined);
        }
        return;
    }
    const initSocket = socketConnect.socketStrategy?.initSocket;
    if (typeof initSocket === 'function') {
        const result = initSocket();
        if (isThenable(result)) {
            void Promise.resolve(result).catch(() => undefined);
        }
    }
}

export function isBossMessageSenderReady(sender: unknown): boolean {
    const candidate = sender as OfficialChatWebsocket | undefined;
    if (!candidate || typeof candidate.sendText !== 'function' || typeof candidate.client?.isConnected !== 'function') {
        return false;
    }
    try {
        return candidate.client.isConnected() === true;
    } catch {
        return false;
    }
}

function isThenable(value: unknown): value is PromiseLike<unknown> {
    return Boolean(value) && typeof (value as PromiseLike<unknown>).then === 'function';
}

export function hasCurrentBossChatChannel(win: BossChatWindow): boolean {
    if (getOfficialChatWebsocket(win)) {
        return true;
    }
    if (hasLegacyGeekChatClient(win)) {
        return true;
    }
    const socketConnect = getSocketConnect(win);
    return typeof socketConnect?.sendMessage === 'function'
        || typeof socketConnect?.sendTextMessage === 'function';
}

export function hasReadyBossChatChannel(win: BossChatWindow): boolean {
    return Boolean(getOfficialChatWebsocket(win)
        || isReadyGeekChatCore(getSocketConnect(win))
        || hasLegacyGeekChatClient(win));
}

export async function ensureReadyBossChatChannel(win: BossChatWindow, timeoutMs = 8000, intervalMs = 250): Promise<boolean> {
    if (getOfficialChatWebsocket(win) || hasLegacyGeekChatClient(win)) {
        return true;
    }

    const socketConnect = getSocketConnect(win);
    if (!socketConnect) {
        return false;
    }

    if (!isSocketConnected(socketConnect)) {
        try {
            await initializeGeekChatCore(socketConnect);
        } catch {
            // The state poll below is authoritative; initialization may be owned by another page.
        }
    }

    if (isReadyGeekChatCore(socketConnect)) {
        return true;
    }

    const deadline = Date.now() + timeoutMs;
    do {
        if (isReadyGeekChatCore(socketConnect)) {
            return true;
        }
        await new Promise(resolve => setTimeout(resolve, intervalMs));
    } while (Date.now() < deadline);
    return isReadyGeekChatCore(socketConnect) || hasLegacyGeekChatClient(win);
}

export async function sendBossChatMessageAsync(
    win: BossChatWindow,
    userLike: BossChatUserLike,
    payload: string | Record<string, unknown>,
    type: 'text' | 'image',
    legacySender: LegacySender,
    options: BossChatSendOptions = {},
): Promise<BossChatSendResult> {
    const user = normalizeUser(userLike);

    if (type === 'text') {
        const officialChatWebsocket = getOfficialChatWebsocket(win);
        if (officialChatWebsocket?.sendText) {
            try {
                const acknowledgement = officialChatWebsocket.sendText({
                    uid: Number(user.uid),
                    source: Number(user.source || 0),
                    encryptUid: String(user.encryptUid),
                    message: String(payload),
                });
                if (isThenable(acknowledgement)) {
                    await acknowledgement;
                    return {sent: true, confirmed: true, channel: 'official-chatwebsocket'};
                }
                return {sent: true, confirmed: false, channel: 'official-chatwebsocket'};
            } catch (error) {
                return {sent: false, confirmed: false, channel: 'official-chatwebsocket', error};
            }
        }
    }

    const socketConnect = getSocketConnect(win);
    if (isReadyGeekChatCore(socketConnect)) {
        try {
            const sharedWorkerClient = getSharedWorkerClient(socketConnect);
            const acknowledgement = type === 'text' && typeof sharedWorkerClient?.sendTextMessage === 'function'
                ? sharedWorkerClient.sendTextMessage(user, String(payload))
                : type === 'text' && typeof socketConnect?.sendTextMessage === 'function'
                    ? socketConnect.sendTextMessage(user, String(payload))
                    : socketConnect?.sendMessage?.(user, payload, type);
            if (isThenable(acknowledgement)) {
                await acknowledgement;
                return {sent: true, confirmed: true, channel: 'geek-chat-core'};
            }
            return {sent: true, confirmed: false, channel: 'geek-chat-core'};
        } catch (error) {
            return {sent: false, confirmed: false, channel: 'geek-chat-core', error};
        }
    }

    // Older BOSS pages expose the official client only through
    // GeekChatCore.getInstance().getClient().client.send. It is still a
    // platform-owned sender, even though no websocket wrapper is public.
    if (hasLegacyGeekChatClient(win)) {
        try {
            const acknowledgement = legacySender();
            if (isThenable(acknowledgement)) {
                await acknowledgement;
                return {sent: true, confirmed: true, channel: 'geek-chat-core'};
            }
            if (acknowledgement) {
                return {sent: true, confirmed: false, channel: 'geek-chat-core'};
            }
        } catch (error) {
            return {sent: false, confirmed: false, channel: 'geek-chat-core', error};
        }
    }

    if (options.officialOnly) {
        return {sent: false, confirmed: false, channel: 'none'};
    }

    try {
        if (typeof socketConnect?.sendMessage === 'function') {
            const acknowledgement = socketConnect.sendMessage(user, payload, type);
            if (isThenable(acknowledgement)) {
                await acknowledgement;
                return {sent: true, confirmed: true, channel: 'geek-chat-core'};
            }
            return {sent: true, confirmed: false, channel: 'geek-chat-core'};
        }
        if (type === 'text' && typeof socketConnect?.sendTextMessage === 'function') {
            const acknowledgement = socketConnect.sendTextMessage(user, String(payload));
            if (isThenable(acknowledgement)) {
                await acknowledgement;
                return {sent: true, confirmed: true, channel: 'geek-chat-core'};
            }
            return {sent: true, confirmed: false, channel: 'geek-chat-core'};
        }
        if (!options.officialOnly || options.allowCompatibilityFallback) {
            const acknowledgement = legacySender();
            if (isThenable(acknowledgement)) {
                await acknowledgement;
                return {sent: true, confirmed: true, channel: 'legacy'};
            }
            if (acknowledgement) {
                return {sent: true, confirmed: false, channel: 'legacy'};
            }
        }
    } catch (error) {
        return {sent: false, confirmed: false, channel: 'none', error};
    }
    return {sent: false, confirmed: false, channel: 'none'};
}

export function sendBossChatMessage(
    win: BossChatWindow,
    userLike: BossChatUserLike,
    payload: string | Record<string, unknown>,
    type: 'text' | 'image',
    legacySender: LegacySender,
): {sent: boolean; channel: 'geek-chat-core' | 'legacy' | 'none'} {
    const user = normalizeUser(userLike);
    const socketConnect = getSocketConnect(win);

    // A successful SDK call is terminal. Falling through after it could send a duplicate.
    if (typeof socketConnect?.sendMessage === 'function') {
        socketConnect.sendMessage(user, payload, type);
        return {sent: true, channel: 'geek-chat-core'};
    }
    if (type === 'text' && typeof socketConnect?.sendTextMessage === 'function') {
        socketConnect.sendTextMessage(user, String(payload));
        return {sent: true, channel: 'geek-chat-core'};
    }
    if (legacySender()) {
        return {sent: true, channel: 'legacy'};
    }
    return {sent: false, channel: 'none'};
}
