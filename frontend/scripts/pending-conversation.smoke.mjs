import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const bossPlatform = readFileSync(new URL('../src/platform/bossPlatform.ts', import.meta.url), 'utf8');
const friendSource = readFileSync(new URL('../src/platform/friendSource.ts', import.meta.url), 'utf8');

assert.match(
  bossPlatform,
  /readFriendSource\(/,
  'Vue 3 contact props must be supported when finding the last HR message',
);
assert.match(friendSource, /component\.props/);
assert.match(friendSource, /lastMessageFromUid/);

console.log('pending conversation source smoke: PASS');
