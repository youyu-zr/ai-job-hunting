import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

// Only silent login proves the BOSS identity. Resume import runs after that application
// session exists, so it must use the server session rather than accepting a second client
// supplied BOSS uid/token pair.
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const readSource = (relativePath: string): string =>
    readFileSync(path.join(projectRoot, relativePath), 'utf-8');

const silentLoginEndpoints = [
    {file: 'src/utils/tools.ts', label: 'tools.ts'},
];
const resumeImportEndpoint = {file: 'src/components/ui/AiJob.vue', label: 'AiJob.vue'};
const identityEndpoints = [...silentLoginEndpoints, resumeImportEndpoint];

test('every silently/login call sends the BOSS token', () => {
    for (const {file, label} of silentLoginEndpoints) {
        const source = readSource(file);
        const calls = source.split('/api/user/silently/login').slice(1);
        assert.ok(calls.length > 0, `${label} should call silently/login`);

        for (const call of calls) {
            // Only inspect the immediate axios invocation, not the rest of the file.
            const invocation = call.slice(0, 200);
            assert.match(
                invocation,
                /Zp_token/,
                `silently/login in ${label} must send Zp_token or any caller can claim another uid`,
            );
        }
    }
});

test('resume import uses the application session instead of client BOSS identity fields', () => {
    for (const {file, label} of [resumeImportEndpoint]) {
        const source = readSource(file);
        const calls = source.split('/api/user/import/resume').slice(1);
        assert.ok(calls.length > 0, `${label} should call import/resume`);

        for (const call of calls) {
            const invocation = call.slice(0, 300);
            assert.doesNotMatch(
                invocation,
                /Zp_token|formData\.append\(['"]uniqueId['"]/,
                `import/resume in ${label} must not accept client BOSS ownership fields`,
            );
            assert.match(invocation, /bossIdentityContext/);
        }
    }
});

test('resume import is not hidden in the silent-login helper', () => {
    const source = readSource('src/utils/tools.ts');
    assert.doesNotMatch(source, /\/api\/user\/import\/resume/);
});

test('the BOSS token comes from the current cookie, never from a stale page snapshot or storage', () => {
    for (const {file, label} of identityEndpoints) {
        const source = readSource(file);
        assert.match(source, /getCookieValue\(["']bst["']\)/, `${label} should read the live bst cookie`);
        assert.doesNotMatch(source, /_PAGE\?\.token/, `${label} must not fall back to a page token snapshot`);
        assert.doesNotMatch(
            source,
            /localStorage\.getItem\(\s*['"]Zp_token['"]/,
            `${label} must not persist the BOSS token to localStorage`,
        );
    }
});
test('a missing uid is rejected with the identity error, not a server error', () => {
    // Silent login still requires a claimed BOSS uid to be checked against the live token.
    const controller = readSource(
        '../backend/src/main/java/com/maple/ai/job/hunting/controller/UserController.java',
    );
    assert.match(
        controller,
        /loginSilently\(@RequestParam\(required = false\) String uniqueId/,
        'uniqueId must be optional so a malformed request reaches the identity check',
    );
});
