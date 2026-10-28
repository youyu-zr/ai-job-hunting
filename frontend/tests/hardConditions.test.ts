import assert from 'node:assert/strict';
import test from 'node:test';
import {checkHardConditions, matchesExcludeList, monthlySalaryUpperK} from '../src/platform/hardConditions.ts';

test('monthly salary upper bound is read from common BOSS formats', () => {
    assert.equal(monthlySalaryUpperK('12-18K·13薪'), 18);
    assert.equal(monthlySalaryUpperK('20-40K·16薪'), 40);
    assert.equal(monthlySalaryUpperK('8-12k'), 12);
    assert.equal(monthlySalaryUpperK('8千-1万'), 10);
    assert.equal(monthlySalaryUpperK('8千-1.2万'), 12);
    assert.equal(monthlySalaryUpperK('1-1.5万'), 15);
    assert.equal(monthlySalaryUpperK('15K'), 15);
    assert.equal(monthlySalaryUpperK('15K·13薪'), 15, '13薪 is the number of months, not the amount');
});

test('formats we cannot read reliably are let through', () => {
    for (const value of ['10K以上', '1万以上', '15K以上·13薪', '10K以上·14薪', '8K起',
        '200-300元/天', '500元/天', '2-3K/周', '30-60万/年', '底薪4K+提成', '3-5K·底薪',
        '5000-8000元/月', '6000-8000', '面议', '']) {
        assert.equal(monthlySalaryUpperK(value), null, value);
    }
});

test('salary floor only rejects when the page upper bound is clearly below it', () => {
    const pref = {salaryFloorK: 12};
    assert.ok(checkHardConditions(pref, {salaryDesc: '6-9K'}));
    assert.equal(checkHardConditions(pref, {salaryDesc: '10-12K'}), null, 'upper equal to floor is still ok');
    assert.equal(checkHardConditions(pref, {salaryDesc: '10K以上'}), null);
    assert.equal(checkHardConditions({salaryFloorK: 15}, {salaryDesc: '15K·13薪'}), null);
    assert.equal(checkHardConditions(pref, {salaryDesc: '150-200元/天'}), null);
});

test('blocked companies match the page name containing what the user typed', () => {
    const pref = {blockedCompanies: ['某某科技有限公司', ' 外包大厂 ', '腾讯']};
    assert.ok(checkHardConditions(pref, {brandName: '某某科技'}), 'suffix 有限公司 is ignored');
    assert.ok(checkHardConditions(pref, {brandName: '外包大厂(上海)'}));
    assert.ok(checkHardConditions(pref, {brandName: '腾讯音乐'}));
    assert.equal(checkHardConditions(pref, {brandName: '另一家公司'}), null);
});

test('a longer blocked name does not block the shorter parent brand', () => {
    assert.equal(checkHardConditions({blockedCompanies: ['腾讯音乐娱乐']}, {brandName: '腾讯'}), null);
    assert.equal(checkHardConditions({blockedCompanies: ['上海科技']}, {brandName: '科技'}), null);
    assert.equal(checkHardConditions({blockedCompanies: ['某']}, {brandName: '某某科技'}), null,
        'single-character entries are ignored');
});

test('two-character names only match at the start and generic words must match exactly', () => {
    assert.equal(checkHardConditions({blockedCompanies: ['京东']}, {brandName: '北京东方'}), null);
    assert.ok(checkHardConditions({blockedCompanies: ['京东']}, {brandName: '京东物流'}));
    assert.ok(checkHardConditions({blockedCompanies: ['华为']}, {brandName: '华为'}));
    assert.equal(checkHardConditions({blockedCompanies: ['华为']}, {brandName: '中华为民'}), null);
    assert.equal(checkHardConditions({blockedCompanies: ['科技公司']}, {brandName: '某某科技'}), null);
    assert.ok(checkHardConditions({blockedCompanies: ['科技公司']}, {brandName: '科技'}));
});

test('avoid tags are left to the AI when AI screening is on', () => {
    const pref = {avoidTags: ['销售']};
    assert.equal(checkHardConditions(pref, {jobName: '售前技术支持(非销售)'}, {semanticScreeningEnabled: true}), null);
    assert.ok(checkHardConditions(pref, {jobName: '电话销售专员'}, {semanticScreeningEnabled: false}));
    assert.equal(checkHardConditions(pref, {jobName: 'Java开发工程师'}, {semanticScreeningEnabled: false}), null);
});

test('exclude lists match one way and ignore blank names and one-letter entries', () => {
    assert.equal(matchesExcludeList(['外包'], '某某外包服务'), '外包');
    assert.equal(matchesExcludeList(['外包'], ''), null, 'missing page name must not block everything');
    assert.equal(matchesExcludeList(['外包'], undefined), null);
    assert.equal(matchesExcludeList(['a'], 'alibaba'), null);
    assert.equal(matchesExcludeList(['某某外包服务有限公司'], '外包'), null, 'no reverse containment');
});

test('empty or old preferences never block anything', () => {
    assert.equal(checkHardConditions(undefined, {brandName: 'A公司', jobName: '销售', salaryDesc: '3-4K'}), null);
    assert.equal(checkHardConditions({}, {brandName: 'A公司', jobName: '销售', salaryDesc: '3-4K'}), null);
});
