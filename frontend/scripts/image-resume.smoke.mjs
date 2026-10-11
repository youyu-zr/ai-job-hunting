import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const platform = readFileSync(new URL('../src/platform/platform.ts', import.meta.url), 'utf8');
const preference = readFileSync(new URL('../src/components/ui/Preference.vue', import.meta.url), 'utf8');
const preferenceEntity = readFileSync(new URL('../../backend/src/main/java/com/maple/ai/job/hunting/model/entity/PreferenceEntity.java', import.meta.url), 'utf8');

assert.match(platform, /const imageParts = String\(customerImageSet\)\.split\("===", 2\);/);
assert.match(platform, /if \(!imageParts\[0\] \|\| !imageParts\[1\]\)/);
assert.match(platform, /const sent = message\.send\(\);/);
assert.match(platform, /POST_DELIVERY_IMAGE[\s\S]*?SendStateTracker\.record\(trace, 'FAILED'/);
assert.match(preference, /const originImageUrl = String\(response\?\.zpData\?\.url \|\| ''\)\.trim\(\);/);
assert.match(preference, /if \(!originImageUrl \|\| !tinyImageUrl\)/);
assert.match(preferenceEntity, /private boolean cIE;/);
assert.match(preferenceEntity, /private String cI;/);

console.log('image resume smoke: PASS');
