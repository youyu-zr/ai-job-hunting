import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';
import {shouldDelegateConversationTextToAi} from '../src/platform/conversationInput.ts';
import {allowedPlatformBodyTypeOf} from '../src/webSocket/packetMessage.ts';

test('browser no longer makes semantic promotion decisions from message words', () => {
    const bossPlatform = readFileSync(new URL('../src/platform/bossPlatform.ts', import.meta.url), 'utf8');
    const marketingGate = readFileSync(new URL('../src/platform/marketingGate.ts', import.meta.url), 'utf8');

    assert.equal(bossPlatform.includes('shouldSilencePromotionalMessage'), false);
    assert.equal(/HARD_PROMOTIONAL_RISKS|PROMOTION_CONTEXT|FUNNEL_ACTIONS/.test(marketingGate), false);
    assert.equal(bossPlatform.includes('return AiPower.ask('), true);
    assert.equal(bossPlatform.includes('消息内容较长，未自动回复；请打开会话查看'), true);
    assert.equal(bossPlatform.includes('allowedPlatformBodyTypeOf(firstItem.msgObj)'), true);
    const oversizedStart = bossPlatform.indexOf('if (normalizedBatch.tooLong)');
    const oversizedEnd = bossPlatform.indexOf('// 记录本轮的暂停代次', oversizedStart);
    const oversizedBranch = bossPlatform.slice(oversizedStart, oversizedEnd);
    assert.equal(oversizedStart >= 0 && oversizedEnd > oversizedStart, true);
    assert.equal(oversizedBranch.includes('markBatchMessagesUnresolved(batch)'), true);
    assert.equal(oversizedBranch.includes('markBatchRepliesAsSent'), false);
});

test('ordinary keyword-bearing HR text reaches server classification with structured type', () => {
    const aiPower = readFileSync(new URL('../src/platform/aiPower.ts', import.meta.url), 'utf8');
    const message = '系统通知：招聘抖音运营，负责账号内容，也会处理收费客户咨询';
    const packet = {messages: [{from: {uid: '42'}, body: {type: 1, text: message}}]};

    assert.equal(shouldDelegateConversationTextToAi(message), true);
    assert.equal(allowedPlatformBodyTypeOf(packet), 1);
    assert.equal(/platformBodyType:\s*\[1,\s*4,\s*8,\s*12,\s*14\]/.test(aiPower), true);
});
