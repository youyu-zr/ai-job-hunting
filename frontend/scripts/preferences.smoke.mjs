import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const preference = readFileSync(new URL('../src/components/ui/Preference.vue', import.meta.url), 'utf8');
const platform = readFileSync(new URL('../src/platform/platform.ts', import.meta.url), 'utf8');
const userService = readFileSync(new URL('../../backend/src/main/java/com/maple/ai/job/hunting/service/biz/UserService.java', import.meta.url), 'utf8');

assert.doesNotMatch(preference, /phone:\s*\[\{\s*required:\s*true/);
assert.doesNotMatch(preference, /email:\s*\[\{\s*required:\s*true/);
assert.match(preference, /if \(value == null \|\| value\.trim\(\) === ''\) \{\s*callback\(\);/s);
assert.doesNotMatch(preference, /if \(!userStore\.user\.phone \|\| !userStore\.user\.email\)/);
assert.match(preference, /userStore\.user\.phone\s*=\s*["']/);
assert.match(userService, /if \(userInfoVO\.getEmail\(\) != null\)/);
assert.match(userService, /if \(userInfoVO\.getPhone\(\) != null\)/);
assert.match(platform, /AiPower\.SEMANTIC_SCREENING_PROMPT/);
assert.doesNotMatch(platform, /候选人优先方向包括/);

console.log('preferences smoke: PASS');
