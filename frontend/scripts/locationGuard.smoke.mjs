import assert from 'node:assert/strict';
import {
  evaluateLocationConsistency,
  normalizeCityName,
  readSearchCity,
} from '../src/platform/locationGuard.ts';

assert.equal(normalizeCityName('上海市浦东新区'), '上海浦东');
assert.equal(normalizeCityName('商丘 睢阳区'), '商丘睢阳');

const config = {
  enabled: true,
  resumeCity: '商丘',
  targetCities: ['商丘'],
  mismatchThreshold: 2,
};
const first = evaluateLocationConsistency(
  {searchCity: '上海', jobCity: '上海浦东新区'},
  config,
  0,
);
const second = evaluateLocationConsistency(
  {searchCity: '上海', jobCity: '上海浦东新区'},
  config,
  first.nextMismatchCount,
);
assert.equal(first.action, 'WARN');
assert.equal(second.action, 'PAUSE');
assert.match(second.reason, /商丘/);

const matching = evaluateLocationConsistency(
  {searchCity: '商丘', jobCity: '商丘睢阳区'},
  config,
  1,
);
assert.equal(matching.action, 'ALLOW');
assert.equal(matching.nextMismatchCount, 0);

const root = {
  querySelectorAll(selector) {
    return selector === '.header-city' ? [{textContent: '商丘'}] : [];
  },
};
assert.equal(readSearchCity(root), '商丘');

console.log('locationGuard smoke: PASS');
