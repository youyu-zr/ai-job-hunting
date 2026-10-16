import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';

const verifier = readFileSync(new URL('../scripts/verify-public-userscript.mjs', import.meta.url), 'utf8');
const functionStart = verifier.indexOf('function canonicalizeBuildPaths(script) {');
const functionEnd = verifier.indexOf('const failures =', functionStart);
assert.ok(functionStart >= 0 && functionEnd > functionStart, 'build canonicalizer must be present');

const canonicalizeBuildPaths = new Function(
    `${verifier.slice(functionStart, functionEnd)}; return canonicalizeBuildPaths;`,
)() as (script: string) => string;

test('normalizes Vue-generated animation hashes across operating systems', () => {
    const linux = '.status-running{animation:seat-pulse-89188554 1.6s ease-in-out}@keyframes seat-pulse-89188554{}';
    const windows = '.status-running{animation:seat-pulse-8191eb10 1.6s ease-in-out}@keyframes seat-pulse-8191eb10{}';

    assert.equal(canonicalizeBuildPaths(linux), canonicalizeBuildPaths(windows));
});

test('keeps real userscript behavior changes visible to the parity check', () => {
    assert.notEqual(
        canonicalizeBuildPaths('sendReply(true)'),
        canonicalizeBuildPaths('sendReply(false)'),
    );
});
