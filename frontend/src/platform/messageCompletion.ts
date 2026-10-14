export function isCompletedMessage(
    entry: {completed?: boolean; expiration: number} | undefined,
    now = Date.now(),
): boolean {
    return entry?.completed === true && entry.expiration > now;
}
