import assert from 'node:assert/strict';
import test from 'node:test';
import {didAiSeatStatusChange} from '../src/stores/seatState.ts';

test('a remote account-level seat change invalidates turns on other devices', () => {
    assert.equal(didAiSeatStatusChange(true, false), true);
    assert.equal(didAiSeatStatusChange(false, true), true);
});

test('unchanged or unknown seat status does not invalidate active turns', () => {
    assert.equal(didAiSeatStatusChange(true, true), false);
    assert.equal(didAiSeatStatusChange(false, false), false);
    assert.equal(didAiSeatStatusChange(undefined, false), false);
    assert.equal(didAiSeatStatusChange(true, undefined), false);
});
