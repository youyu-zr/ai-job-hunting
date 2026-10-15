import assert from 'node:assert/strict';
import test from 'node:test';
import {MessageMergeQueue} from '../src/platform/messageMerge.ts';

async function collectBatches(messages: Array<{type: number; text: string}>) {
    const queue = new MessageMergeQueue<typeof messages[number]>(1);
    const batches: number[][] = [];
    await Promise.all(messages.map(message => queue.enqueue(
        'job-1',
        message.text,
        message,
        batch => {
            batches.push(batch.items.map(item => item.type));
            return true;
        },
        String(message.type),
    )));
    return batches;
}

test('does not merge ordinary text followed by an interview card', async () => {
    assert.deepEqual(await collectBatches([
        {type: 1, text: 'HR text'},
        {type: 14, text: 'Interview card'},
    ]), [[1], [14]]);
});

test('does not merge an interview card followed by ordinary text', async () => {
    assert.deepEqual(await collectBatches([
        {type: 14, text: 'Interview card'},
        {type: 1, text: 'HR text'},
    ]), [[14], [1]]);
});

test('continues coalescing distinct messages with the same body type', async () => {
    assert.deepEqual(await collectBatches([
        {type: 1, text: 'First HR text'},
        {type: 1, text: 'Second HR text'},
    ]), [[1, 1]]);
});

test('continues deduplicating repeated text within the same body type', async () => {
    assert.deepEqual(await collectBatches([
        {type: 1, text: 'Repeated HR text'},
        {type: 1, text: 'Repeated HR text'},
    ]), [[1]]);
});
