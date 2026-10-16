import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const source = readFileSync(new URL('../src/platform/aiPower.ts', import.meta.url), 'utf8');
assert.match(source, /const jobContext = await this\.getJobContextText\(bossUserInfo\)/);
assert.match(source, /jobContext,\s*aiFilterDecision/);
assert.doesNotMatch(source, /visibleJobCard,\s*aiFilterDecision/);

console.log('chat context smoke: PASS');
