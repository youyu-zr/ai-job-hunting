import assert from 'node:assert/strict';
import test from 'node:test';
import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

test('failure cleanup preserves terminal messages but not in-flight markers', async () => {
    const url = new URL('../src/platform/messageCompletion.ts', import.meta.url);
    const module = existsSync(fileURLToPath(url))
        ? await import('../src/platform/messageCompletion.ts')
        : {};
    const isCompleted = typeof module.isCompletedMessage === 'function'
        ? module.isCompletedMessage
        : () => false;

    assert.equal(isCompleted({completed: true, expiration: 200}, 100), true);
    assert.equal(isCompleted({completed: false, expiration: 200}, 100), false);
    assert.equal(isCompleted({expiration: 200}, 100), false);
    assert.equal(isCompleted({completed: true, expiration: 50}, 100), false);
    assert.equal(isCompleted(undefined, 100), false);
});
