import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const platform = readFileSync(new URL('../src/platform/platform.ts', import.meta.url), 'utf8');
const presentation = readFileSync(new URL('../src/logging/presentation.ts', import.meta.url), 'utf8');
const runRecord = readFileSync(new URL('../src/components/ui/RunRecord.vue', import.meta.url), 'utf8');

assert.doesNotMatch(platform, /compact\.slice\(0, 180\)/);
assert.match(platform, /formatAiFilterSummary/);
assert.match(presentation, /function normalizeLegacyFilterRecord/);
assert.match(runRecord, /label="错误" value="error"/);
assert.match(runRecord, /class="log-message"/);
assert.doesNotMatch(runRecord, /\.log-message\s*\{[\s\S]*?max-height:\s*240px/);
assert.doesNotMatch(runRecord, /\.log-message\s*\{[\s\S]*?overflow-y:\s*auto/);

console.log('filter log readable smoke: PASS');
