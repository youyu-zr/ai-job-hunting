import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

const tools = readFileSync(new URL('../src/utils/tools.ts', import.meta.url), 'utf8');
const loginState = readFileSync(new URL('../src/utils/silentLoginState.ts', import.meta.url), 'utf8');
const aiJob = readFileSync(new URL('../src/components/ui/AiJob.vue', import.meta.url), 'utf8');
const userService = readFileSync(
  new URL('../../backend/src/main/java/com/maple/ai/job/hunting/service/biz/UserService.java', import.meta.url),
  'utf8',
);

test('first login creates an account after BOSS identity verification without importing a resume', () => {
  const loginMethod = userService.slice(userService.indexOf('public String loginSilently'));
  // Ownership is still decided before any account is created; only the decision's shape changed,
  // because "BOSS could not tell us" is no longer the same answer as "BOSS named someone else".
  const identityCheck = loginMethod.indexOf('bossIdentityVerifier.authorizeLoginOrThrow(normalizedUniqueId, bossToken)');
  const accountCreation = loginMethod.indexOf('new UserInfoDO(null, null, normalizedUniqueId)');

  assert.ok(identityCheck >= 0);
  assert.ok(accountCreation > identityCheck);
  assert.match(loginMethod, /betaAccessService\.ensureAutoGrant\(account\.getId\(\)\)/);
  assert.match(loginState, /response\?\.data\?\.code === 2000/);
  assert.match(tools, /if \(outcome === "legacy-registration-required"\)/);
  assert.match(tools, /这不是简历未导入导致的/);
  assert.doesNotMatch(tools, /handlerImport|\/api\/user\/import\/resume/);
});

test('resume upload is wired to the user-triggered import button', () => {
  assert.match(aiJob, /@click="handlerImport"/);
  assert.match(aiJob, /const handlerImport = async/);
  assert.match(aiJob, /axios\.post\("\/api\/user\/import\/resume"/);
});

test('resetting preferences persists the local defaults before contacting the server', () => {
  const preference = readFileSync(new URL('../src/components/ui/Preference.vue', import.meta.url), 'utf8');
  const resetStart = preference.indexOf('const resetForm = async');
  const requestStart = preference.indexOf('await axios.post("/api/user/save/preference"', resetStart);
  const resetFlow = preference.slice(resetStart, requestStart);

  assert.ok(resetStart >= 0);
  assert.ok(requestStart > resetStart);
  assert.match(resetFlow, /preferenceDefaultValueHandler\(\)/);
  assert.match(resetFlow, /persistLocalUserConfig\(\)/);
});
