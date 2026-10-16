import assert from 'node:assert/strict';
import test from 'node:test';
import {filterLogs, getLatestPushLogs, isPushRecord, readAllLogs} from '../src/logging/eventStream.ts';

/** 模拟 LogRecorder：只保留 id/level/message/timestamp，按写入顺序存储。 */
function createRecorder(entries: Array<{level?: string; message: string; timestamp?: string}>, maxLogs = 1000) {
    let logs = entries.map((entry, index) => ({
        eventId: `evt-${index + 1}`,
        level: entry.level || 'info',
        message: entry.message,
        timestamp: entry.timestamp || '10:00:00',
    }));
    return {
        getLogCount: () => logs.length,
        getLogs: (page: number, pageSize: number) => logs.slice((page - 1) * pageSize, (page - 1) * pageSize + pageSize),
        getLatestEventId: () => (logs.length ? logs[logs.length - 1].eventId : null),
        getLogByEventId: (eventId: string) => logs.find(log => log.eventId === eventId),
        push: (message: string, level = 'info') => {
            logs.push({eventId: `evt-${logs.length + 1000}`, level, message, timestamp: '10:00:01'});
            if (logs.length > maxLogs) {
                logs = logs.slice(logs.length - maxLogs);
            }
        },
        trimTo: (size: number) => { logs = logs.slice(logs.length - size); },
    } as any;
}

test('the floating panel reads the same single event stream as the run record', () => {
    const recorder = createRecorder([
        {message: '开始投递'},
        {message: '工作【前端】投递成功'},
        {message: '无关的调试输出'},
    ]);

    const all = readAllLogs(recorder);
    const push = getLatestPushLogs(recorder, 10, null);

    assert.deepEqual(all.map(log => log.eventId), ['evt-3', 'evt-2', 'evt-1']);
    assert.deepEqual(push.map(log => log.eventId), ['evt-1', 'evt-2']);
    push.forEach(log => {
        assert.deepEqual(recorder.getLogByEventId(log.eventId), log);
    });
});

test('event ids stay unique so both views cannot merge two events into one row', () => {
    const recorder = createRecorder([
        {message: '开始投递'},
        {message: '开始投递'},
        {message: '开始投递'},
    ]);

    const ids = readAllLogs(recorder).map(log => log.eventId);

    assert.equal(new Set(ids).size, ids.length);
});

test('trimming old logs does not shift the floating panel start point', () => {
    const recorder = createRecorder([{message: '开始投递'}]);
    const startEventId = recorder.getLatestEventId();
    recorder.push('工作【A】投递成功');
    recorder.push('工作【B】投递成功');

    const beforeTrim = getLatestPushLogs(recorder, 10, startEventId);
    recorder.trimTo(2);
    recorder.push('工作【C】投递成功');
    const afterTrim = getLatestPushLogs(recorder, 10, startEventId);

    assert.deepEqual(beforeTrim.map(log => log.message), ['工作【A】投递成功', '工作【B】投递成功']);
    assert.deepEqual(afterTrim.map(log => log.message), ['工作【A】投递成功', '工作【C】投递成功']);
});

test('no start event id means every push event is shown', () => {
    const recorder = createRecorder([
        {message: '工作【A】投递成功'},
        {message: '工作【B】投递成功'},
    ]);

    assert.deepEqual(getLatestPushLogs(recorder, 10, null).map(log => log.message), [
        '工作【A】投递成功',
        '工作【B】投递成功',
    ]);
});

test('a start id that was already dropped does not hide later events', () => {
    const recorder = createRecorder([{message: '开始投递'}, {message: '工作【A】投递成功'}]);
    const startEventId = recorder.getLatestEventId();
    recorder.trimTo(1);
    recorder.push('工作【B】投递成功');

    assert.deepEqual(getLatestPushLogs(recorder, 10, startEventId).map(log => log.message), [
        '工作【B】投递成功',
    ]);
});

test('an empty recorder produces no rows in either view', () => {
    const recorder = createRecorder([]);

    assert.deepEqual(readAllLogs(recorder), []);
    assert.deepEqual(getLatestPushLogs(recorder, 10, null), []);
    assert.equal(recorder.getLatestEventId(), null);
});

test('push filtering and log filtering stay separate concerns', () => {
    assert.equal(isPushRecord({eventId: 'evt-1', level: 'info', message: '开始投递', timestamp: '10:00:00'}), true);
    assert.equal(isPushRecord({eventId: 'evt-2', level: 'debug', message: '消息通道初始化', timestamp: '10:00:00'}), false);

    const logs = [
        {eventId: 'evt-1', level: 'info', message: '开始投递', timestamp: '10:00:00'},
        {eventId: 'evt-2', level: 'error', message: '投递失败', timestamp: '10:00:05'},
    ];
    assert.deepEqual(filterLogs(logs, {level: 'error'}).map(log => log.eventId), ['evt-2']);
    assert.deepEqual(filterLogs(logs, {keyword: '开始'}).map(log => log.eventId), ['evt-1']);
});

console.log('log event stream smoke: PASS');