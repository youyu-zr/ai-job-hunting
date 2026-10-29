import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {formatDeliverySuccessLog} from '../src/logging/presentation.ts';
import {formatAiFilterReasonForDelivery} from '../src/platform/filterGate.ts';

const platform = readFileSync(new URL('../src/platform/platform.ts', import.meta.url), 'utf8');

// A successful delivery must carry the screening reason in the same user-visible
// record. The standalone "AI screening passed" record is redundant in that flow.
assert.match(
  platform,
  /const semanticScreeningEnabled = Boolean\(userStore\.user\.preference\.afE\)[\s\S]*?const filterDecision = semanticScreeningEnabled[\s\S]*?AiPower\.getCachedFilterDecision\(jobDetail\)[\s\S]*?formatAiFilterReasonForDelivery\(filterDecision\)[\s\S]*?formatDeliverySuccessLog\(\{/,
  'successful delivery should render the cached AI screening reason',
);
assert.doesNotMatch(platform, /AI岗位分析通过/);
assert.equal(
  formatDeliverySuccessLog({
    companyName: '示例科技',
    jobName: '全栈工程师',
    salaryDesc: '8-12K',
    location: '上海浦东',
    reason: '岗位方向匹配，风险较低',
  }),
  '公司【示例科技】；岗位【全栈工程师】投递成功；薪资：8-12K；地点：上海浦东；原因：岗位方向匹配，风险较低',
);
assert.equal(
  formatDeliverySuccessLog({
    companyName: '示例科技',
    jobName: '全栈工程师',
    reason: '通过当前筛选条件',
  }),
  '公司【示例科技】；岗位【全栈工程师】投递成功；原因：通过当前筛选条件',
);
const longReason = '完整原因'.repeat(80);
assert.equal(
  formatDeliverySuccessLog({
    companyName: '示例科技',
    jobName: '全栈工程师',
    reason: longReason,
  }),
  `公司【示例科技】；岗位【全栈工程师】投递成功；原因：${longReason}`,
);
assert.equal(
  formatAiFilterReasonForDelivery({
    reason: '岗位方向匹配，信息完整',
    goodSignals: ['技能方向匹配', '经验要求宽松'],
    riskSignals: ['需要确认办公方式', '需要确认面试流程', '需要确认到岗时间'],
  }),
  '岗位方向匹配，信息完整；正向信息：技能方向匹配、经验要求宽松；风险信息：需要确认办公方式、需要确认面试流程、需要确认到岗时间',
);
assert.match(
  platform,
  /if \(pushResult\.message === 'Success' && pushResult\.code === 0\) \{[\s\S]*?formatDeliverySuccessLog\(\{/,
  'only the confirmed success branch should write the success record',
);

console.log('delivery success log smoke: PASS');
