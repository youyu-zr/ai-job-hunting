import assert from 'node:assert/strict';
import test from 'node:test';
import {isRangeOverlap, parseLooseRange} from '../src/platform/looseRange.ts';
const Tools = {isRangeOverlap, parseLooseRange};

test('BOSS salary and company-size labels are parsed into numeric ranges', () => {
    assert.deepEqual(Tools.parseLooseRange('15-25K·13薪'), [15, 25]);
    assert.deepEqual(Tools.parseLooseRange('8-12K'), [8, 12]);
    assert.deepEqual(Tools.parseLooseRange('100-499人'), [100, 499]);
    assert.deepEqual(Tools.parseLooseRange('10000人以上'), [10000, Number.POSITIVE_INFINITY]);
    assert.deepEqual(Tools.parseLooseRange('20人以下'), [0, 20]);
    assert.equal(Tools.parseLooseRange('200-300元/天'), null);
    assert.equal(Tools.parseLooseRange('面议'), null);
    assert.equal(Tools.parseLooseRange(''), null);
});

test('salary filter now actually rejects a job below the wanted range', () => {
    assert.equal(Tools.isRangeOverlap('15-20', '8-12K·13薪'), false);
    assert.equal(Tools.isRangeOverlap('10-15', '12-18K·14薪'), true);
    assert.equal(Tools.isRangeOverlap('12以上', '8-11K'), false);
});

test('company size filter no longer throws on "人" and unknown labels pass through', () => {
    assert.equal(Tools.isRangeOverlap('100-1000', '100-499人'), true);
    assert.equal(Tools.isRangeOverlap('1000以上', '20-99人'), false);
    assert.equal(Tools.isRangeOverlap('8-12', '200-300元/天'), true);
});
