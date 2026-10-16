type MessageSocket = {
    addEventListener?: (type: string, listener: (event: {data: unknown}) => void) => void;
};

type DispatchEventTarget = {
    dispatchEvent?: (event: any) => boolean;
};

type DispatchObserver = (
    target: object,
    event: {type?: string; data?: unknown},
) => void;

const installedSockets = new WeakSet<object>();
const installedDispatchPrototypes = new WeakSet<object>();

/**
 * Attach one independent receive observer to a BOSS WebSocket.
 *
 * The observer deliberately does not replace `onmessage`: BOSS may assign that
 * property before or after the socket is created, and replacing it makes the
 * AI seat depend on that timing. A separate listener sees both cases.
 */
export function installReceiveListener(
    socket: MessageSocket,
    onData: (data: unknown) => void,
): boolean {
    if (!socket || typeof socket.addEventListener !== 'function') {
        return false;
    }
    const key = socket as object;
    if (installedSockets.has(key)) {
        return false;
    }
    installedSockets.add(key);
    socket.addEventListener('message', event => {
        onData(event?.data);
    });
    return true;
}

export function hasReceiveListener(socket: object | null | undefined): boolean {
    return Boolean(socket && installedSockets.has(socket));
}

/**
 * Hook the prototype event dispatcher so sockets created before the userscript
 * loaded are observable even when they never call send() after installation.
 * The wrapper always delegates to the native dispatcher; an observer failure
 * must never break BOSS's own message delivery.
 */
export function installReceiveDispatchHook(
    prototype: DispatchEventTarget,
    observer: DispatchObserver,
): boolean {
    if (!prototype || typeof prototype.dispatchEvent !== 'function') {
        return false;
    }
    const key = prototype as object;
    if (installedDispatchPrototypes.has(key)) {
        return false;
    }

    const nativeDispatchEvent = prototype.dispatchEvent;
    installedDispatchPrototypes.add(key);
    prototype.dispatchEvent = function (event) {
        try {
            if (!hasReceiveListener(this as object)) {
                observer(this as object, event);
            }
        } catch {
            // Observability must be isolated from the platform event pipeline.
        }
        return nativeDispatchEvent.call(this, event);
    };
    return true;
}
