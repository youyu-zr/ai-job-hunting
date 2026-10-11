/**
 * 事件流查询的纯函数实现。
 *
 * 这里不依赖 LogRecorder / 平台工具，node 可以直接导入测试；
 * src/logging/queries.ts 复用同一份实现，保证线上路径与单测路径一致。
 */

/** 右侧浮窗与运行记录共用的最小事件形状。 */
export type LogEventRecord = { eventId: string; level: string; message: string; timestamp: string };

export type LogEventSource = {
    getLogCount: () => number;
    getLogs: (page: number, pageSize: number) => LogEventRecord[];
};

export type LogFilter = {
    timeRange?: Date[];
    level?: string;
    keyword?: string;
};

const PUSH_LOG_MARKERS = [
    "开始投递",
    "结束投递",
    "无下一页数据",
    "当前筛选条件下岗位均已投递",
    "投递成功",
    "工作【",
    "AI岗位分析",
    "投递后",
    "岗位化招呼语",
    "图片简历",
    "地区一致性",
    "简历发送",
];

/**
 * 读取全部事件，按事件 ID 去重并保持“最新在前”的顺序。
 *
 * 浮窗和运行记录都调用这里，因此两处看到的是同一个事件源、同一套 id 和
 * 同一个顺序，不再各自持有独立的读取管道。
 */
export function readAllLogs(source: LogEventSource): LogEventRecord[] {
    const count = source.getLogCount();
    if (count <= 0) {
        return [];
    }
    const records = source.getLogs(1, count).slice().reverse();
    const seen = new Set<string>();
    return records.filter((record) => {
        const eventId = String(record?.eventId || '');
        if (!eventId || seen.has(eventId)) {
            return false;
        }
        seen.add(eventId);
        return true;
    });
}

/** 写入顺序的事件流（最旧在前），供时间线判断使用。 */
export function readLogsInWriteOrder(source: LogEventSource): LogEventRecord[] {
    return readAllLogs(source).slice().reverse();
}

/** 浮窗与运行记录共同使用的投递事件筛选，保证两处条数一致。 */
export function isPushRecord(log: LogEventRecord): boolean {
    const message = String(log?.message || "").toLowerCase();
    return PUSH_LOG_MARKERS.some((marker) => message.includes(marker.toLowerCase()));
}

/**
 * 浮窗使用的最近投递事件。
 *
 * 起点用稳定的事件 ID 而不是日志条数：日志超过上限被裁剪时，按条数取起点会
 * 整体偏移，导致浮窗显示的记录和运行记录对不上。
 */
export function getLatestPushLogs(source: LogEventSource, limit = 10, startEventId: string | null = null): LogEventRecord[] {
    const logsInWriteOrder = readLogsInWriteOrder(source);
    const startIndex = startEventId
        ? logsInWriteOrder.findIndex((log) => String(log.eventId) === String(startEventId)) + 1
        : 0;
    return logsInWriteOrder
        .slice(Math.max(0, startIndex))
        .filter(isPushRecord)
        .slice(-limit);
}

export function filterLogs(logs: LogEventRecord[], filter: LogFilter): LogEventRecord[] {
    let result = logs;
    if (filter.timeRange?.length === 2) {
        const [start, end] = filter.timeRange.map((time) => time.toTimeString().slice(0, 6) + "00");
        result = result.filter((log) => log.timestamp >= start && log.timestamp <= end);
    }
    if (filter.level) {
        result = result.filter((log) => log.level === filter.level);
    }
    if (filter.keyword) {
        const keyword = filter.keyword.toLowerCase();
        result = result.filter((log) => String(log.message || "").toLowerCase().includes(keyword));
    }
    return result;
}

export function getRecordLevelClass(level: string): string {
    switch (String(level || "").toLowerCase()) {
        case "error": return "record-error";
        case "warn": return "record-warn";
        case "info": return "record-info";
        case "debug": return "record-debug";
        case "trace": return "record-trace";
        default: return "record-info";
    }
}