import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

// The backend now refuses to issue a session for a claimed BOSS uid unless the caller also
// presents the BOSS token for that uid. These assertions keep that proof attached to every
// identity-bearing request, so a future refactor cannot silently reopen the forgery hole.
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

test('every import/resume call sends the BOSS token', () => {
    for (const {file, label} of [resumeImportEndpoint]) {
        const source = readSource(file);
        const calls = source.split('/api/user/import/resume').slice(1);
        assert.ok(calls.length > 0, `${label} should call import/resume`);

        for (const call of calls) {
            const invocation = call.slice(0, 300);
            assert.match(
                invocation,
                /Zp_token/,
                `import/resume in ${label} must send Zp_token`,
            );
        }
    }
});

test('resume import is not hidden in the silent-login helper', () => {
    const source = readSource('src/utils/tools.ts');
    assert.doesNotMatch(source, /\/api\/user\/import\/resume/);
});

test('the BOSS token always comes from the live page, never from persisted state', () => {
    for (const {file, label} of identityEndpoints) {
        const source = readSource(file);
        assert.match(source, /_PAGE\?\.token/, `${label} should read the token from _PAGE`);
        assert.doesNotMatch(
            source,
            /localStorage\.getItem\(\s*['"]Zp_token['"]/,
            `${label} must not persist the BOSS token to localStorage`,
        );
    }
});
test('a missing uid is rejected with the identity error, not a server error', () => {
    // Guards the contract behind a malformed request (for example a JSON body instead of a form
    // post): the caller must get 2003 identity rejection rather than a 500, so a probing client
    // cannot use the response shape to tell a missing uid from a rejected one.
    const controller = readSource(
        '../backend/src/main/java/com/maple/ai/job/hunting/controller/UserController.java',
    );
    assert.match(
        controller,
        /loginSilently\(@RequestParam\(required = false\) String uniqueId/,
        'uniqueId must be optional so a malformed request reaches the identity check',
    );
});
