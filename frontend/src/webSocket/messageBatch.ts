/** Packets cross a trust boundary, so `messages` may be absent or not an array at runtime. */
export type ChatProtocolBatch = {messages?: unknown};

/**
 * Split a protocol packet so each downstream handler processes one message.
 *
 * Malformed batches (missing `messages`, non-array `messages`, empty array) and
 * single-message batches are returned unchanged inside a one-element array, so callers can
 * always iterate the result. Multi-message batches are split in order; every split packet
 * keeps the outer metadata and owns a fresh `messages` array instead of sharing the original.
 */
export function splitChatProtocolMessages<T extends ChatProtocolBatch>(wsData: T): T[] {
    const messages = Array.isArray(wsData?.messages) ? (wsData.messages as unknown[]) : null;
    if (!messages || messages.length <= 1) {
        return [wsData];
    }
    return messages.map(message => ({...wsData, messages: [message]}) as T);
}
