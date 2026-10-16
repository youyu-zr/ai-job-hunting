import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const platform = readFileSync(new URL('../src/platform/platform.ts', import.meta.url), 'utf8');
const protobuf = readFileSync(new URL('../src/webSocket/protobuf.ts', import.meta.url), 'utf8');
const presentation = readFileSync(new URL('../src/logging/presentation.ts', import.meta.url), 'utf8');
const record = readFileSync(new URL('../src/logging/record.ts', import.meta.url), 'utf8');
const aiJob = readFileSync(new URL('../src/components/ui/AiJob.vue', import.meta.url), 'utf8');
const runRecord = readFileSync(new URL('../src/components/ui/RunRecord.vue', import.meta.url), 'utf8');

assert.match(platform, /await waitForMessageSender\(/);
assert.match(platform, /await waitForMessageSender\(\)/);
assert.match(platform, /await message\.sendAndConfirm\(\)/);
assert.doesNotMatch(platform, /do \{[\s\S]*?sent = message\.send\(\)[\s\S]*?while \(!sent/);
assert.match(protobuf, /ChatWebsocketImage\.send\(this\)/);
assert.match(protobuf, /async sendAndConfirm\(/);
assert.match(protobuf, /await sendBossChatMessageAsync\(/);
assert.match(protobuf, /if \(result\.confirmed\)[\s\S]*?SendStateTracker\.record\(this\.trace, 'SEND_CONFIRMED'/);
assert.match(readFileSync(new URL('../src/platform/sendState.ts', import.meta.url), 'utf8'), /public static async waitForConfirmation\(/);
assert.match(protobuf, /function getMessageSenders\(\)[\s\S]*?ChatWebsocketImage/);
assert.match(protobuf, /for \(const \[name, sender\] of getMessageSenders\(\)\)/);
assert.doesNotMatch(protobuf, /async \(\) => \{[\s\S]*?client\.send\(this\)/);
assert.match(protobuf, /client\.send\(this\);\s*return true;/);
// 状态机必须区分"已提交"与"已确认"，未证实状态不能被当成发送成功。
assert.match(presentation, /status !== 'SEND_SUBMITTED' && status !== 'SEND_CONFIRMED' && status !== 'UNCONFIRMED' && status !== 'FAILED'/);
assert.match(presentation, /UNCONFIRMED: '未确认，需人工核实'/);
assert.match(readFileSync(new URL('../src/platform/sendStatus.ts', import.meta.url), 'utf8'), /export function isConfirmedStatus\(status: SendStatus\): boolean \{\s*return status === 'SEND_CONFIRMED';\s*\}/);
assert.match(readFileSync(new URL('../src/platform/sendStatus.ts', import.meta.url), 'utf8'), /export function isRetryableStatus\(status: SendStatus\): boolean \{\s*return status === 'FAILED' \|\| status === 'UNCONFIRMED';\s*\}/);
assert.match(record, /if \(!visibleMessage\) \{\s*return;\s*\}/);
assert.match(aiJob, /setTimeout\(\(\) => \{[\s\S]*?startRecordsUpdate\(\)/);
assert.match(runRecord, /setInterval\(fetchLogs, 500\)/);
assert.match(runRecord, /onUnmounted/);

console.log('delivery send and log smoke: PASS');
