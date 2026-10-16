import assert from 'node:assert/strict';
import {messageFingerprint, normalizeMessageFingerprint} from '../src/platform/messageFingerprint.ts';

assert.equal(normalizeMessageFingerprint('  方便   了解一下？\n'), '方便 了解一下？');
assert.notEqual(
  messageFingerprint(7, '同样的开头-第一条'),
  messageFingerprint(7, '同样的开头-第二条'),
  'messages sharing a prefix must not collide by truncation',
);
assert.equal(messageFingerprint(7, '同一条消息'), messageFingerprint(7, ' 同一条消息 '));

console.log('message fingerprint smoke: PASS');
