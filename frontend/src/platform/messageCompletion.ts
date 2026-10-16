export function isCompletedMessage(
    entry: {completed?: boolean; expiration: number} | undefined,
    now = Date.now(),
): boolean {
    return entry?.completed === true && entry.expiration > now;
}

/** Terminal local dedupe marker for an item that was not automatically answered. */
export function terminalWithoutReply(expiration: number): {
    expiration: number;
    completed: true;
    replySent: false;
} {
    return {expiration, completed: true, replySent: false};
}
