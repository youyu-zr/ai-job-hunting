import logger, {Logger, LogLevel} from "./index";
import { TampermonkeyApi } from "../platform/utils";
import {formatUserVisibleLog} from "./presentation";

/**
 * 右侧浮窗与运行记录共用的一条事件。
 *
 * eventId 在写入时生成且稳定不变，浮窗和运行记录按同一个 id 读取同一条事件，
 * 因此两处展示的条数、顺序和内容必须一致，不需要各自再做一次日志筛选。
 */
export type LogRecord = { eventId: string; level: string; message: string; timestamp: string };

export class LogRecorder extends Logger {
    private static readonly LOGS_STORAGE_KEY = "logs_data";
    private static readonly EVENT_ID_PREFIX = "evt";
    private static sequence = 0;
    private persistTimer: number | null = null;
    private static logs: LogRecord[] = [];

    constructor(name: string = "") {
        super(name);
        this.loadLogsFromStorage();
        this.startPersistTimer();
    }

    /**
     * 生成单调递增的事件 ID。
     *
     * 同一毫秒内的多条日志也不会重名，刷新页面后仍按 id 保持既有顺序，
     * 因此不会出现“浮窗第 7 条”和“运行记录第 7 条”其实是两条不同事件的情况。
     */
    private static newEventId(): string {
        LogRecorder.sequence += 1;
        return `${LogRecorder.EVENT_ID_PREFIX}-${LogRecorder.sequence}`;
    }

    /**
     * 读取磁盘旧日志时补齐 eventId / 写入顺序。
     *
     * 历史日志只有 timestamp，重新载入后必须继续拥有唯一 id，否则浮窗和
     * 运行记录的按键去重会把不同事件合并成一条。
     */
    private static normalizeStoredLog(log: any): LogRecord | null {
        const message = formatUserVisibleLog(log?.message || '');
        if (!message) {
            return null;
        }
        const timestamp = String(log?.timestamp || '');
        const eventId = String(log?.eventId || '') || `${LogRecorder.EVENT_ID_PREFIX}-legacy-${timestamp}-${LogRecorder.sequence += 1}`;
        return {eventId, level: String(log?.level || 'info'), message, timestamp};
    }

    private loadLogsFromStorage() {
        const storedLogs = TampermonkeyApi.GmGetValue(LogRecorder.LOGS_STORAGE_KEY, []);
        if (!Array.isArray(storedLogs)) {
            return;
        }
        const existingIds = new Set(LogRecorder.logs.map(log => log.eventId));
        const normalizedStoredLogs = storedLogs
            .map((log: any) => LogRecorder.normalizeStoredLog(log))
            .filter((log: LogRecord | null): log is LogRecord => log !== null);
        const uniqueStoredLogs = normalizedStoredLogs.filter(log => !existingIds.has(log.eventId));
        LogRecorder.logs = [...LogRecorder.logs, ...uniqueStoredLogs];
        const changed = normalizedStoredLogs.length !== storedLogs.length
            || normalizedStoredLogs.some((log, index) => log.eventId !== storedLogs[index]?.eventId
                || log.message !== storedLogs[index]?.message);
        if (changed) {
            TampermonkeyApi.GmSetValue(LogRecorder.LOGS_STORAGE_KEY, normalizedStoredLogs);
        }
    }

    private startPersistTimer() {
        this.persistTimer = window.setInterval(() => {
            this.persistLogs();
        }, 10000);
    }

    private persistLogs() {
        TampermonkeyApi.GmSetValue(LogRecorder.LOGS_STORAGE_KEY, LogRecorder.logs);
    }

    public clearLogs() {
        LogRecorder.logs = [];
        this.persistLogs();
    }

    // 设置日志存储的最大条数
    private maxLogs = 1000;

    private addLog(level: string, message: string) {
        const timestamp = new Date().toLocaleTimeString();
        const visibleMessage = formatUserVisibleLog(message);
        if (!visibleMessage) {
            return;
        }
        LogRecorder.logs.push({
            eventId: LogRecorder.newEventId(),
            level,
            message: visibleMessage,
            timestamp,
        });
        if (LogRecorder.logs.length > this.maxLogs) {
            // 超过最大条数时移除最旧的一条日志
            LogRecorder.logs.shift();
        }
    }

    error(...messages: any[]) {
        const msg = messages.join(' ');
        this.addLog('error', msg);
        super.error(msg);
    }

    warn(...messages: any[]) {
        const msg = messages.join(' ');
        this.addLog('warn', msg);
        super.warn(msg);
    }

    info(...messages: any[]) {
        const msg = messages.join(' ');
        this.addLog('info', msg);
        super.info(msg);
    }

    debug(...messages: any[]) {
        const msg = messages.join(' ');
        this.addLog('debug', msg);
        super.debug(msg);
    }

    trace(...messages: any[]) {
        const msg = messages.join(' ');
        this.addLog('trace', msg);
        super.trace(msg);
    }

    // 获取日志数据，支持分页
    getLogs(page: number, pageSize: number): LogRecord[] {
        const start = (page - 1) * pageSize;
        return LogRecorder.logs.slice(start, start + pageSize);
    }

    // 获取日志总条数
    getLogCount() {
        return LogRecorder.logs.length;
    }

    /**
     * 最近一条事件的 ID。
     *
     * 浮窗用它标记“本次投递的起点”；事件 ID 不会因为旧日志被裁剪而变化，
     * 因此长时间运行后浮窗与运行记录仍然从同一条事件开始显示。
     */
    getLatestEventId(): string | null {
        const latest = LogRecorder.logs[LogRecorder.logs.length - 1];
        return latest ? latest.eventId : null;
    }

    /** 按事件 ID 读取单条事件，浮窗和运行记录共享同一份数据。 */
    getLogByEventId(eventId: string): LogRecord | undefined {
        return LogRecorder.logs.find(log => log.eventId === eventId);
    }
}