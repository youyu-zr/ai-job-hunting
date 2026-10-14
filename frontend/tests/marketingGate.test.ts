import assert from 'node:assert/strict';
import test from 'node:test';
import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

test('marketing messages are filtered locally before an older server can call AI', async () => {
    const moduleUrl = new URL('../src/platform/marketingGate.ts', import.meta.url);
    const gate = existsSync(fileURLToPath(moduleUrl))
        ? await import('../src/platform/marketingGate.ts')
        : {};
    const shouldSilence = typeof gate.shouldSilencePromotionalMessage === 'function'
        ? gate.shouldSilencePromotionalMessage
        : () => false;

    assert.equal(shouldSilence('扫码进群获取资料', false), true);
    assert.equal(shouldSilence('岗位职责：负责课程推广，指导用户注册后完成门店资料录入', false), false);
    assert.equal(shouldSilence('岗位职责包括推广活动，扫码进群后每日签到和录入门店客户资料', false), false);
    assert.equal(shouldSilence('课程顾问工作职责：添加微信维护学员日常沟通', false), false);
    assert.equal(shouldSilence('岗位不涉及培训贷另有押金', true), true);
    assert.equal(shouldSilence('岗位不涉及收费培训，不要求缴费', true), false);
    assert.equal(shouldSilence('招聘平台工作人员负责核验并退还历史入会费', true), false);
    assert.equal(
        shouldSilence('岗位不要求求职者缴纳入会费，客服负责处理缴费和退款咨询', true),
        false,
    );
    assert.equal(
        shouldSilence('无需求职者缴纳入会费，客服负责处理用户缴费和退款咨询', true),
        false,
    );
    assert.equal(shouldSilence('先交押金后安排培训', true), true);
    assert.equal(shouldSilence('缴纳入会费后开始接单', true), true);
    assert.equal(shouldSilence('课程顾问岗位，添加微信领取课程资料', true), false);
});
