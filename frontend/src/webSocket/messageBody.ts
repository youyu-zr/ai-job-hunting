/**
 * 纯函数消息体解析。
 *
 * 这里的实现不依赖 mqtt/protobuf，因此 node 可以直接导入测试；
 * src/webSocket/utils.ts 复用同一份实现，保证线上路径与测试路径一致。
 */

/**
 * 单条消息的文本兜底解码。
 *
 * BOSS 一个 chat 包里可以合法携带多条消息。此前只有 messages[0] 会被解码，
 * 同一个包里后续的 HR 消息 body 为空，会被下游当成空消息丢掉。
 */
export function decodeMessageBody(message: any, payloadText: string, caller: string): boolean {
    const body = message?.body;
    if (!body || typeof body !== "object") {
        return false;
    }
    // boss索要简历,交换电话，交换微信等信息时，body.type=7
    if (body.type === 7) {
        // 这里解码没数据，手动解码Uint8Array格式的base64数据
        body.text = payloadText;
    }

    // 兜底解码
    if (!body.text && caller !== "发送") {
        body.text = payloadText;
    }
    return true;
}

/** 把包内所有消息补齐文本，返回实际处理的消息条数。 */
export function decodeMessageBatch(messages: any, payloadText: string, caller: string): number {
    if (!Array.isArray(messages)) {
        return 0;
    }
    messages.forEach(message => decodeMessageBody(message, payloadText, caller));
    return messages.length;
}

/** 单个 body 到可读文本的映射，getMsgBody 与批量解析共用。 */
export function mapMessageBody(body: any): string {
    const normalize = (value: any) => String(value || "").replace(/\s+/g, " ").trim();
    const compact = (...values: any[]) => values.map(normalize).filter(Boolean).join(" ");
    // BOSS uses body.type=7 for exchange-style structured messages. A resume
    // request has a resume payload; a resumeShare is an already-shared resume
    // and must not be mistaken for a request.
    if (body?.type === 7 && body.resume) {
        return "HR明确请求发送简历（结构化消息）";
    }
    if (body?.type === 7 && body.resumeShare) {
        return "对方已分享简历（结构化消息）";
    }
    if (body?.text) {
        return body.text;
    }
    if (body?.jobDesc) {
        const job = body.jobDesc;
        return compact("职位卡片：", job.title, job.company, job.salary, job.experience, job.education, job.city, job.content);
    }
    if (body?.jobShare) {
        const job = body.jobShare;
        return compact("职位分享：", job.position, job.company, job.salary, job.experience, job.education, job.location, job.description);
    }
    if (body?.interviewShare) {
        const interview = body.interviewShare;
        return compact("面试信息：", interview.title, interview.jobName, interview.interviewTime, interview.interviewAddress);
    }
    if (body?.interview) {
        return compact("面试信息：", body.interview.text, body.interview.extend);
    }
    if (body?.dialog) {
        return normalize(body.dialog.text || body.dialog.content || body.dialog.title);
    }
    if (body?.notify) {
        return normalize(body.notify.text || body.notify.title);
    }
    if (body?.hyperLink) {
        return normalize(body.hyperLink.text || body.hyperLink.url);
    }
    if (body?.sticker) {
        return `[${body.sticker.name || "动画表情"}]`;
    }
    if (body?.image) {
        return "[图片]";
    }
    return "";
}

/**
 * 取整包的可读文本。
 *
 * 单条消息包与历史行为完全一致；多条消息包会从最后一条开始向前找第一个能产出
 * 文本的元素，避免同一个包里的后续 HR 消息被静默丢掉。
 */
export function getMsgBody(data: any): string {
    const messages = data?.messages;
    if (!Array.isArray(messages) || messages.length === 0) {
        return "";
    }
    for (let index = messages.length - 1; index >= 0; index--) {
        const text = mapMessageBody(messages[index]?.body || {});
        if (text) {
            return text;
        }
    }
    return "";
}

const MAX_NORMALIZED_MESSAGE_LENGTH = 5000;

/** Keep the full normalized text so an oversized batch cannot be classified by prefix. */
export function normalizedMessageBatch(texts: unknown[]): {
    text: string;
    tooLong: boolean;
} {
    const text = (Array.isArray(texts) ? texts : [])
        .map(value => String(value ?? "").replace(/\s+/g, " ").trim())
        .filter(Boolean)
        .join(" ");
    return {text, tooLong: text.length > MAX_NORMALIZED_MESSAGE_LENGTH};
}
