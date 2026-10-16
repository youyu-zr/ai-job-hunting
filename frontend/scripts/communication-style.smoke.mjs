import fs from 'node:fs';

const preference = fs.readFileSync(new URL('../src/components/ui/Preference.vue', import.meta.url), 'utf8');
const types = fs.readFileSync(new URL('../src/stores/types.ts', import.meta.url), 'utf8');
const remote = fs.readFileSync(new URL('../src/stores/remote.ts', import.meta.url), 'utf8');

for (const value of ['NATURAL', 'PROACTIVE']) {
  if (!preference.includes('label="' + value + '"')) throw new Error('missing style option: ' + value);
}
if (preference.includes('label="CAUTIOUS"')) throw new Error('removed style option is still exposed');
for (const field of ['jobGoal', 'unacceptableConditions', 'clarifyConditions', 'communicationPreferences', 'communicationStyle']) {
  if (!types.includes(field)) throw new Error('missing type field: ' + field);
  if (!remote.includes('preference.' + field)) throw new Error('missing default field: ' + field);
}
if (preference.includes('v-model="userStore.user.preference.af"')) {
  throw new Error('screening rule textarea must not be exposed as a personal preference');
}
if (preference.includes('v-model="userStore.user.preference.crK"') || preference.includes('v-model="userStore.user.preference.crC"')) {
  throw new Error('legacy high-interest keyword/round controls must not be exposed');
}
console.log('communication-style smoke passed');
