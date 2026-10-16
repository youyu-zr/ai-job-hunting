/**
 * 发送状态机的纯函数部分。
 *
 * 这里不依赖浏览器存储与平台工具，node 可以直接导入测试；
 * src/platform/sendState.ts 复用同一份定义，保证线上路径与测试路径一致。
 */

/**
 * 发送状态。
 *
 * 关键区分：
 * - SEND_SUBMITTED = 平台已接受本次提交，但还没有拿到本人回显，属于"未证实"；
 * - SEND_CONFIRMED = 拿到了本人消息回显或平台回执，才是真正可对用户宣称的发送成功；
 * - FAILED        = 没有任何通道接受这次提交，可以安全重试；
 * - UNCONFIRMED   = 提交过但超时仍未证实，不能被当成成功。
 */
export type SendStatus =
    'GENERATED'
    | 'QUEUED'
    | 'INPUT_FILLED'
    | 'SEND_CLICKED'
    | 'SEND_SUBMITTED'
    | 'SEND_CONFIRMED'
    | 'UNCONFIRMED'
    | 'FAILED';

/** 仍在进行中、尚未得出最终结论的状态。 */
const IN_FLIGHT_STATUSES: ReadonlySet<SendStatus> = new Set<SendStatus>([
    'GENERATED', 'QUEUED', 'INPUT_FILLED', 'SEND_CLICKED', 'SEND_SUBMITTED',
]);

/** 终态：不会再自动变化的状态。 */
const TERMINAL_STATUSES: ReadonlySet<SendStatus> = new Set<SendStatus>([
    'SEND_CONFIRMED', 'UNCONFIRMED', 'FAILED',
]);

export function isInFlightStatus(status: SendStatus): boolean {
    return IN_FLIGHT_STATUSES.has(status);
}

/** 只有拿到平台确认才算成功。 */
export function isConfirmedStatus(status: SendStatus): boolean {
    return status === 'SEND_CONFIRMED';
}

export function isTerminalStatus(status: SendStatus): boolean {
    return TERMINAL_STATUSES.has(status);
}

/** 失败或未证实都可以重试；已确认不能重发。 */
export function isRetryableStatus(status: SendStatus): boolean {
    return status === 'FAILED' || status === 'UNCONFIRMED';
}