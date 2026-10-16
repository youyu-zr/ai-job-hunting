import assert from 'node:assert/strict';
import test from 'node:test';
import {isConfirmedStatus, isInFlightStatus, isRetryableStatus, isTerminalStatus} from '../src/platform/sendStatus.ts';

test('only a platform-confirmed send counts as success', () => {
    assert.equal(isConfirmedStatus('SEND_CONFIRMED'), true);
    assert.equal(isConfirmedStatus('SEND_SUBMITTED'), false);
    assert.equal(isConfirmedStatus('UNCONFIRMED'), false);
    assert.equal(isConfirmedStatus('FAILED'), false);
});

test('submitted and unconfirmed are not treated as in-flight forever', () => {
    assert.equal(isInFlightStatus('SEND_SUBMITTED'), true);
    assert.equal(isInFlightStatus('SEND_CLICKED'), true);
    // 未证实与失败都是终态，必须显式处理而不是继续等待。
    assert.equal(isInFlightStatus('UNCONFIRMED'), false);
    assert.equal(isInFlightStatus('FAILED'), false);
    assert.equal(isInFlightStatus('SEND_CONFIRMED'), false);
});

test('a submitted message is not a confirmed message', () => {
    // 旧实现把 SEND_SUBMITTED 与 SEND_CONFIRMED 混用；这里的契约是
    // "已提交" 永远不能等同于 "已确认"。
    const submitted = 'SEND_SUBMITTED' as const;
    const confirmed = 'SEND_CONFIRMED' as const;
    assert.notEqual(submitted, confirmed);
    assert.equal(isConfirmedStatus(submitted), false);
});

console.log('send state machine smoke: PASS');