import assert from 'node:assert/strict';
import { MessageMergeQueue } from '../src/platform/messageMerge.ts';

const queue = new MessageMergeQueue(40);
const seen = [];

const first = queue.enqueue('job-1:boss-1', '您好，我看了您的简历', {id: 1}, async (batch) => {
  seen.push(batch);
  return true;
});
const second = queue.enqueue('job-1:boss-1', '方便了解一下岗位吗？', {id: 2}, async (batch) => {
  seen.push(batch);
  return true;
});

assert.equal(first, second, 'same HR window should share one processing promise');
assert.equal(await first, true);
assert.equal(seen.length, 1);
assert.deepEqual(seen[0].texts, ['您好，我看了您的简历', '方便了解一下岗位吗？']);
assert.deepEqual(seen[0].items.map((item) => item.id), [1, 2]);

const afterWindow = queue.enqueue('job-1:boss-1', '请问工作地点在哪里？', {id: 3}, async (batch) => {
  seen.push(batch);
  return true;
});
assert.equal(await afterWindow, true);
assert.equal(seen.length, 2);
assert.deepEqual(seen[1].texts, ['请问工作地点在哪里？']);

const differentJob = queue.enqueue('job-2:boss-1', '另一个职位的消息', {id: 4}, async (batch) => {
  seen.push(batch);
  return true;
});
assert.equal(await differentJob, true);
assert.equal(seen.length, 3);
assert.deepEqual(seen[2].texts, ['另一个职位的消息']);

console.log('message merge smoke: PASS');
