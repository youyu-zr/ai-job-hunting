import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';

const codeqlWorkflow = readFileSync(
    new URL('../../.github/workflows/codeql.yml', import.meta.url),
    'utf8',
);

test('CodeQL runs on pull requests targeting release branches', () => {
    const pullRequestTriggers = codeqlWorkflow
        .slice(codeqlWorkflow.indexOf('  pull_request:'), codeqlWorkflow.indexOf('  schedule:'));

    assert.match(pullRequestTriggers, /branches:\s*\n\s+- main\s*\n\s+- ['"]?release\/\*\*['"]?/);
});
