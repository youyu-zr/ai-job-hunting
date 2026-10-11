import assert from 'node:assert/strict';
import fs from 'node:fs';

const bossMessage = fs.readFileSync(new URL('../src/components/ui/BossMessage.vue', import.meta.url), 'utf8');
const utils = fs.readFileSync(new URL('../src/webSocket/utils.ts', import.meta.url), 'utf8');
const messageBody = fs.readFileSync(new URL('../src/webSocket/messageBody.ts', import.meta.url), 'utf8');
const aiPower = fs.readFileSync(new URL('../src/platform/aiPower.ts', import.meta.url), 'utf8');
const product = fs.readFileSync(new URL('../src/components/ui/Product.vue', import.meta.url), 'utf8');
const invitationExchange = fs.readFileSync(new URL('../src/components/ui/InvitationExchange.vue', import.meta.url), 'utf8');

assert.match(aiPower, /session\/status/);
assert.match(bossMessage, /status: 'WAITING'/);
assert.match(bossMessage, /status === 'RUNNING'/);
assert.match(bossMessage, /status: 'ERROR'/);
assert.match(bossMessage, /seatStatus\.label/);
assert.match(bossMessage, /v-if="seatStatus\.manuallyPaused"/);
assert.match(bossMessage, /恢复当前会话/);
assert.doesNotMatch(bossMessage, /重启当前会话AI坐席/);
// 结构化简历请求文案已迁到纯函数模块，utils 继续对外转发同一实现。
assert.match(messageBody, /"HR明确请求发送简历（结构化消息）"/);
assert.match(messageBody, /结构化消息/);
assert.match(utils, /export \{decodeMessageBody, decodeMessageBatch, mapMessageBody, getMsgBody\} from "\.\/messageBody";/);
assert.match(product, /periodOfValidityEndTime/);
assert.match(invitationExchange, /periodOfValidityEndTime/);

console.log('ai-seat-runtime-and-resume smoke: PASS');
