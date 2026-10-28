import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';
import {UnreadRetryBackoff} from '../src/platform/unreadRetryBackoff.ts';

test('failures stretch the retry interval and success resets it', () => {
    const backoff = new UnreadRetryBackoff();
    const t0 = 1_000_000;
    assert.equal(backoff.isDue('b', 'hi', t0), true);
    assert.equal(backoff.recordFailure('b', 'hi', t0), 15_000);
    assert.equal(backoff.isDue('b', 'hi', t0 + 10_000), false);
    assert.equal(backoff.isDue('b', 'hi', t0 + 15_000), true);
    assert.equal(backoff.recordFailure('b', 'hi', t0), 30_000);
    assert.equal(backoff.recordFailure('b', 'hi', t0), 60_000);
    // another contact is unaffected
    assert.equal(backoff.isDue('other', 'hi', t0), true);
    backoff.clear('b', 'hi');
    assert.equal(backoff.isDue('b', 'hi', t0), true);
});

test('interval caps at 10 minutes and pauses an hour after 8 failures', () => {
    const backoff = new UnreadRetryBackoff();
    const waits = Array.from({length: 8}, () => backoff.recordFailure('b', 'x', 0));
    assert.deepEqual(waits.slice(0, 7), [15_000, 30_000, 60_000, 120_000, 240_000, 480_000, 600_000]);
    assert.equal(waits[7], 3_600_000);
    // after the long pause it starts over from 15s
    assert.equal(backoff.recordFailure('b', 'x', 0), 15_000);
});

test('scanner uses the backoff and moves on to other contacts', () => {
    const source = readFileSync(new URL('../src/platform/bossPlatform.ts', import.meta.url), 'utf8');
    assert.match(source, /unreadRetryBackoff\.isDue\(bossId, text\)/);
    assert.match(source, /unreadRetryBackoff\.recordFailure\(bossId, text\)/);
    assert.match(source, /unreadRetryBackoff\.clear\(bossId, text\)/);
});
