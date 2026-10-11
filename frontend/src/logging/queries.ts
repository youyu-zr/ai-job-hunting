import {LogRecorder, LogRecord} from "./record";

export type {LogFilter} from "./eventStream";
export {
    filterLogs,
    getLatestPushLogs,
    getRecordLevelClass,
    isPushRecord,
    readAllLogs,
    readLogsInWriteOrder,
} from "./eventStream";

// 兼容既有调用点：LogRecord 现在自带稳定的 eventId。
export type {LogRecord};