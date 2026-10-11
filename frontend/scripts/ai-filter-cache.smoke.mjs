import fs from 'node:fs';
import assert from 'node:assert/strict';

const source = fs.readFileSync(new URL('../src/platform/aiPower.ts', import.meta.url), 'utf8');

assert.match(source, /filterProfileScope\(\)/);
assert.match(source, /GM_getValue\("ck_cur"/);
assert.match(source, /resumeId: profile\?\.resumeId/);
assert.match(source, /preference: profile\?\.preference/);
assert.match(source, /return `\$\{this\.filterProfileScope\(\)\}::\$\{jobKey\}`/);

console.log('ai-filter-cache smoke passed');
