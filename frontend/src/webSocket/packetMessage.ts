/**
 * 单条消息包的字段访问工具。
 *
 * WebSocket 收到的包正常情况下已经由 splitChatProtocolMessages 拆成一条消息一包，
 * 但包本身仍可能来自未读补偿扫描等构造路径，缺失 messages 或 messages 为空。
 * 直接写 `messages[0]` 会在这些路径上抛 TypeError，因此统一走这里的容错读取。
 */

/** 取包内最后一条消息；缺失、非数组或空数组时返回 undefined。 */
export function lastMessageOf(packet: any): any {
    const messages = packet?.messages;
    if (!Array.isArray(messages) || messages.length === 0) {
        return undefined;
    }
    return messages[messages.length - 1];
}

/** 取包内第一条消息；缺失、非数组或空数组时返回 undefined。 */
export function firstMessageOf(packet: any): any {
    const messages = packet?.messages;
    if (!Array.isArray(messages) || messages.length === 0) {
        return undefined;
    }
    return messages[0];
}

/** 取包内某条消息的 body.type；读不到时返回 undefined。 */
export function bodyTypeOf(packet: any): number | undefined {
    return firstMessageOf(packet)?.body?.type;
}

/** 取包内某条消息的 body.image；读不到时返回 undefined。 */
export function bodyImageOf(packet: any): any {
    return firstMessageOf(packet)?.body?.image;
}

/** 取包内某条消息的 type；读不到时返回 undefined。 */
export function msgTypeOf(packet: any): number | undefined {
    return firstMessageOf(packet)?.type;
}

/**
 * 取包内某条消息的发送方信息；整包无消息时返回空字符串字段。
 *
 * 返回字段一律是 string，调用方可以直接拼 HTML，不必再额外兜底。
 */
export function senderOf(packet: any): { name: string; avatar: string } {
    const from = firstMessageOf(packet)?.from;
    return {
        name: String(from?.name ?? ""),
        avatar: String(from?.avatar ?? ""),
    };
}

/** 取包内第一条消息的 mid；读不到时返回 undefined（未读补偿构造的历史消息没有 mid）。 */
export function messageIdOf(packet: any): any {
    return firstMessageOf(packet)?.mid;
}