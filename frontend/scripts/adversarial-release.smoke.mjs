import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {
    formatDeliverySuccessLog,
    formatUserVisibleLog,
} from '../src/logging/presentation.ts';
import {
    formatAiFilterReasonForDelivery,
    shouldBlockAfterAiFilter,
} from '../src/platform/filterGate.ts';

const platform = readFileSync(new URL('../src/platform/platform.ts', import.meta.url), 'utf8');
const panel = readFileSync(new URL('../src/components/ui/AiJob.vue', import.meta.url), 'utf8');
const aiPower = readFileSync(new URL('../src/platform/aiPower.ts', import.meta.url), 'utf8');

// The screening API contract permits only DELIVER or FILTER. Missing facts
// are normalized to DELIVER by the server; malformed or legacy actions fail closed.
assert.equal(shouldBlockAfterAiFilter(null), true);
assert.equal(shouldBlockAfterAiFilter({filter: false, recommendedAction: 'DELIVER', riskLevel: 'LOW'}), false);
assert.equal(shouldBlockAfterAiFilter({filter: false, recommendedAction: 'DELIVER', riskLevel: 'MEDIUM'}), false);
assert.equal(shouldBlockAfterAiFilter({filter: false, recommendedAction: 'DELIVER', riskLevel: 'HIGH'}), true);
assert.equal(shouldBlockAfterAiFilter({filter: true, recommendedAction: 'DELIVER', riskLevel: 'LOW'}), true);
assert.equal(shouldBlockAfterAiFilter({filter: false, recommendedAction: 'UNKNOWN', riskLevel: 'MEDIUM'}), true);
assert.equal(shouldBlockAfterAiFilter({filter: false, recommendedAction: 'FILTER', riskLevel: 'MEDIUM'}), true);

// A long result must retain every explanation and risk signal in the final
// delivery record, while hostile markup remains plain text at the Vue boundary.
const riskSignals = Array.from({length: 24}, (_, index) => `风险信号${index + 1}`);
const hostile = '<img src=x onerror=alert(1)>\u0000';
const reason = `${hostile}\n完整原因 ${'详细说明'.repeat(80)}`;
const successLog = formatDeliverySuccessLog({
    companyName: hostile,
    jobName: '测试岗位',
    salaryDesc: '8-12K',
    location: '上海',
    reason,
});
assert.match(successLog, /完整原因/);
assert.ok(riskSignals.every((signal) => formatAiFilterReasonForDelivery({reason, riskSignals}).includes(signal)));
assert.doesNotMatch(successLog, /\u0000/);
assert.match(successLog, /<img src=x onerror=alert\(1\)>/);
assert.doesNotMatch(panel, /v-html="record\.message"/);
assert.doesNotMatch(panel, /v-html=".*push.*record/i);

// The success branch must use only the current job's matching cached decision
// and must not create a duplicate standalone "screening passed" record.
assert.match(platform, /semanticScreeningEnabled[\s\S]*?getCachedFilterDecision\(jobDetail\)[\s\S]*?formatDeliverySuccessLog\(\{/);
assert.doesNotMatch(platform, /AI岗位分析通过/);
assert.match(aiPower, /return `\$\{serverUrl\}:\$\{accountKey\}:\$\{this\.shortHash\(profileVersion\)\}`/);

// Internal send traces are translated into user-facing text instead of
// exposing their English implementation markers in the delivery panel.
const translated = formatUserVisibleLog('[SEND_STATE] status=SEND_CONFIRMED action=AI_REPLY msg=confirmed by own websocket echo text');
assert.doesNotMatch(translated, /confirmed by own websocket echo/i);

console.log('adversarial release smoke: PASS');
