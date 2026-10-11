import {LogRecorder} from "../logging/record";
import {TampermonkeyApi, Tools} from "./utils";
import {isInFlightStatus, type SendStatus} from "./sendStatus";

// 状态定义与判定集中在 ./sendStatus 的纯函数模块，这里只做转发。
export {isConfirmedStatus, isInFlightStatus, isRetryableStatus, isTerminalStatus} from "./sendStatus";
export type {SendStatus} from "./sendStatus";

export interface SendTraceContext {
    traceId?: string
    jobKey?: string
    bossId?: number | string
    jobTitle?: string
    action?: string
    channel?: string
    content?: string
    cmid?: string
}

type SendStateRecord = SendTraceContext & {
    status: SendStatus
    timestamp: number
    message?: string
}

export class SendStateTracker {
    private static readonly STORAGE_KEY = 'send_state_recent_v1';
    private static readonly MAX_RECORDS = 120;
    private static readonly PENDING_EXPIRE_MS = 3 * 60 * 1000;
    private static readonly logRecorder = new LogRecorder('send-state');

    public static createContext(ctx: SendTraceContext): SendTraceContext {
        if (!ctx.traceId) {
            ctx.traceId = this.newTraceId(ctx);
        }
        return {
            ...ctx,
        };
    }

    public static prepare(ctx: SendTraceContext | undefined, cmid: string, content: string): SendTraceContext | undefined {
        if (!ctx) {
            return undefined;
        }
        const prepared = this.createContext({
            ...ctx,
            cmid,
            content: ctx.content || content,
        });
        this.record(prepared, 'QUEUED', 'message prepared');
        return prepared;
    }

    public static record(ctx: SendTraceContext | undefined, status: SendStatus, message = ''): void {
        if (!ctx) {
            return;
        }
        const record: SendStateRecord = {
            ...this.createContext(ctx),
            status,
            timestamp: Date.now(),
            message,
            content: this.normalizeContent(ctx.content || ''),
        };
        const records = this.loadRecords()
            .filter(item => Date.now() - item.timestamp <= 24 * 60 * 60 * 1000);
        records.push(record);
        while (records.length > this.MAX_RECORDS) {
            records.shift();
        }
        TampermonkeyApi.GmSetValue(this.STORAGE_KEY, records);

        this.logRecorder.info(
            `[SEND_STATE] status=${status}`
            + ` traceId=${record.traceId || ''}`
            + ` jobKey=${record.jobKey || ''}`
            + ` bossId=${record.bossId || ''}`
            + ` action=${record.action || ''}`
            + ` channel=${record.channel || ''}`
            + ` cmid=${record.cmid || ''}`
            + ` msg=${message || ''}`
            + ` content=${this.limit(record.content || '', 80)}`
        );
    }

    public static confirmOwnEcho(msgBody: string, wsData: any): void {
        const now = Date.now();
        const echoCmid = String(wsData?.messages?.[0]?.cmid || '');
        const echoText = this.normalizeContent(msgBody || wsData?.messages?.[0]?.body?.text || '');
        if (!echoCmid && !echoText) {
            return;
        }
        const records = this.loadRecords();
        const alreadyConfirmed = new Set(
            records
                .filter(item => item.status === 'SEND_CONFIRMED')
                .map(item => item.traceId)
        );
        const candidates = records
            .filter(item => !alreadyConfirmed.has(item.traceId))
            .filter(item => item.status === 'SEND_CLICKED'
                || item.status === 'SEND_SUBMITTED'
                || item.status === 'QUEUED')
            .filter(item => now - item.timestamp <= this.PENDING_EXPIRE_MS)
            .reverse();
        const matched = candidates.find(item => {
            if (echoCmid && item.cmid && String(item.cmid) === echoCmid) {
                return true;
            }
            const pendingText = this.normalizeContent(item.content || '');
            return !!pendingText && !!echoText && (pendingText === echoText || pendingText.includes(echoText) || echoText.includes(pendingText));
        });
        if (matched) {
            this.record(matched, 'SEND_CONFIRMED', echoCmid ? 'confirmed by own websocket echo cmid' : 'confirmed by own websocket echo text');
        }
    }

    /**
     * 等待平台确认。
     *
     * 超时仍未拿到回显时，把仍在途的记录落到 UNCONFIRMED，而不是留在
     * SEND_SUBMITTED：这样后续逻辑不会把它当成成功，重试前也能明确知道
     * 这条消息的真实状态是"未知"。
     */
    public static async waitForConfirmation(trace: SendTraceContext | undefined, timeoutMs = 8000, intervalMs = 200): Promise<boolean> {
        if (!trace?.traceId) {
            return false;
        }
        const deadline = Date.now() + timeoutMs;
        do {
            const status = this.currentStatus(trace.traceId);
            if (status === 'SEND_CONFIRMED') {
                return true;
            }
            if (status === undefined) {
                return false;
            }
            await Tools.sleep(intervalMs);
        } while (Date.now() < deadline);
        if (this.currentStatus(trace.traceId) === 'SEND_CONFIRMED') {
            return true;
        }
        const pending = this.loadRecords().find(item =>
            item.traceId === trace.traceId && isInFlightStatus(item.status)
        );
        if (pending) {
            this.record(pending, 'UNCONFIRMED', 'no own-message echo or platform acknowledgement before timeout');
        }
        return false;
    }

    /** 该 trace 的最新状态。 */
    public static currentStatus(traceId: string | undefined): SendStatus | undefined {
        if (!traceId) {
            return undefined;
        }
        const matches = this.loadRecords().filter(item => item.traceId === traceId);
        return matches.length ? matches[matches.length - 1].status : undefined;
    }

    /** 是否存在一条可安全重试的提交（失败或未证实）。 */
    public static hasRetryableSubmit(action: string, bossId: number | string): boolean {
        return this.loadRecords().some(item =>
            item.action === action
            && String(item.bossId || '') === String(bossId || '')
            && (item.status === 'FAILED' || item.status === 'UNCONFIRMED')
        );
    }

    public static hasConfirmation(trace: SendTraceContext | undefined): boolean {
        if (!trace?.traceId) {
            return false;
        }
        return this.loadRecords().some(item =>
            item.traceId === trace.traceId && item.status === 'SEND_CONFIRMED'
        );
    }

    /**
     * The delivery greeting is sent through the same websocket as a manual
     * message. Match its short-lived trace here so the manual-intervention
     * hook does not disable the AI seat for the conversation it just opened.
     */
    public static isTrackedAutomaticOutbound(msgBody: string, wsData: any, bossId: number | string): boolean {
        const now = Date.now();
        const echoCmid = String(wsData?.messages?.[0]?.cmid || '');
        const echoText = this.normalizeContent(msgBody || wsData?.messages?.[0]?.body?.text || '');
        if (!echoCmid && !echoText) {
            return false;
        }
        return this.loadRecords()
            .filter(item => item.action === 'POST_DELIVERY_GREETING')
            .filter(item => String(item.bossId || '') === String(bossId || ''))
            .filter(item => item.status === 'QUEUED'
                || item.status === 'INPUT_FILLED'
                || item.status === 'SEND_CLICKED'
                || item.status === 'SEND_SUBMITTED')
            .filter(item => now - item.timestamp <= this.PENDING_EXPIRE_MS)
            .some(item => {
                if (echoCmid && item.cmid && String(item.cmid) === echoCmid) {
                    return true;
                }
                const pendingText = this.normalizeContent(item.content || '');
                return !!pendingText && !!echoText
                    && (pendingText === echoText || pendingText.includes(echoText) || echoText.includes(pendingText));
            });
    }

    private static loadRecords(): SendStateRecord[] {
        const raw = TampermonkeyApi.GmGetValue(this.STORAGE_KEY, []);
        return Array.isArray(raw) ? raw : [];
    }

    private static normalizeContent(text: string): string {
        return String(text || '')
            .replace(new RegExp(Tools.getEndChar(), 'g'), '')
            .replace(/\s+/g, ' ')
            .trim();
    }

    private static newTraceId(ctx: SendTraceContext): string {
        return `${ctx.action || 'send'}-${ctx.bossId || 'boss'}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    }

    private static limit(text: string, maxLength: number): string {
        const value = String(text || '');
        return value.length <= maxLength ? value : `${value.slice(0, maxLength)}...`;
    }
}
