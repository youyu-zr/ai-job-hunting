import assert from 'node:assert/strict';
import test from 'node:test';
import {formatUserVisibleLog} from '../src/logging/presentation.ts';
import {readAllLogs} from '../src/logging/eventStream.ts';

/** 复刻 LogRecorder 的写入语义：先格式化为用户可见文案，空结果不入库。 */
function createRecorder(messages: string[]) {
    const logs: Array<{eventId: string; level: string; message: string; timestamp: string}> = [];
    messages.forEach((raw) => {
        const visible = formatUserVisibleLog(raw);
        if (!visible) {
            return;
        }
        logs.push({eventId: `evt-${logs.length + 1}`, level: 'info', message: visible, timestamp: '10:00:00'});
    });
    return {getLogCount: () => logs.length, getLogs: (page: number, size: number) => logs.slice((page - 1) * size, (page - 1) * size + size)};
}

test('only user-visible events reach both the panel and the run record', () => {
    const recorder = createRecorder([
        '开始投递',
        '[SEND_STATE] status=INPUT_FILLED traceId=x1 jobKey=前端 bossId=9 action=POST_DELIVERY_GREETING cmid=c1',
        'WS Hook Start',
        '工作【前端】被过滤 原因：不匹配',
    ]);

    const events = readAllLogs(recorder);

    assert.equal(events.length, 3);
    assert.deepEqual(events.map(event => event.message), [
        '工作【前端】被过滤 原因：不匹配',
        '消息通道初始化',
        '开始投递',
    ]);
});

test('user-facing send results become readable Chinese events with a stable id', () => {
    const recorder = createRecorder([
        '[SEND_STATE] status=SEND_CONFIRMED traceId=x2 jobKey=前端 bossId=9 action=POST_DELIVERY_GREETING cmid=c2',
    ]);

    const events = readAllLogs(recorder);

    assert.equal(events.length, 1);
    assert.match(events[0].message, /岗位【前端】投递后招呼语已确认发送/);
    assert.equal(events[0].eventId, 'evt-1');
});

console.log('event visibility smoke: PASS');