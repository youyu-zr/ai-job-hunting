import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {extractGreetingText, isUsableJobGreeting} from '../src/platform/greetingGuard.ts';

const platform = readFileSync(new URL('../src/platform/platform.ts', import.meta.url), 'utf8');
const websocketHook = readFileSync(new URL('../src/webSocket/hookMain.ts', import.meta.url), 'utf8');
const preference = readFileSync(new URL('../src/components/ui/Preference.vue', import.meta.url), 'utf8');
const assistant = readFileSync(new URL('../../backend/src/main/java/com/maple/ai/job/hunting/service/biz/AIAssistantService.java', import.meta.url), 'utf8');

assert.match(platform, /const greetingJobInfo = \{[\s\S]*jobTitle: jobDetail\.jobName[\s\S]*greetingHint:/);
assert.match(platform, /for \(let attempt = 1; attempt <= 2/);
assert.match(platform, /已重试一次，未发送招呼语/);
assert.match(platform, /isUsableJobGreeting\(customGreeting, greetingJobInfo\)/);
assert.match(platform, /customGreeting && !isUsableJobGreeting\(customGreeting, greetingJobInfo\)/);
assert.match(platform, /private async prepareJobGreeting\(jobDetail: BossJobDetail\)/);
assert.match(platform, /await this\.ensureBossAutomaticGreetingDisabled\(\)/);
assert.match(platform, /zpchat\/greeting\/getGreetingList/);
assert.match(platform, /zpchat\/greeting\/updateGreetingV2/);
assert.match(platform, /async pushAfterSendMsg\(jobDetail: BossJobDetail, preparedGreeting: string\)/);
assert.doesNotMatch(platform, /if \(!userStore\.user\.preference\.cgE \|\| this\._pushMock\)/);
assert.match(websocketHook, /SendStateTracker\.isTrackedAutomaticOutbound\(msg, wsData, toUid\)/);
assert.match(websocketHook, /function filter\(msg: string, wsData: any, toUid: number\): boolean/);
assert.doesNotMatch(websocketHook, /msg === userStore\?\.user\?\.preference\?\.cg/);

const doPushStart = platform.indexOf('async doPush(jobDetail: BossJobDetail');
const doPushEnd = platform.indexOf('private bossDataCache', doPushStart);
const doPushBody = platform.slice(doPushStart, doPushEnd);
assert.ok(doPushStart >= 0 && doPushEnd > doPushStart, 'doPush body must be readable');
assert.ok(
  doPushBody.indexOf('await this.prepareJobGreeting(jobDetail)') < doPushBody.indexOf('friend/add.json'),
  'the validated AI greeting must be ready before BOSS creates the conversation',
);
assert.ok(
  doPushBody.indexOf('await this.ensureBossAutomaticGreetingDisabled()') < doPushBody.indexOf('axiosOriginal.post(publishUrl'),
  'the BOSS automatic greeting must be disabled before friend/add',
);
const afterPushStart = platform.indexOf('async pushAfterHandler(pushResult: PushResult, jobDetail: BossJobDetail)');
const afterPushEnd = platform.indexOf('async pushAfterSendMsg(', afterPushStart);
const afterPushBody = platform.slice(afterPushStart, afterPushEnd);
assert.ok(
  afterPushBody.indexOf('await this.pushAfterSendMsg') < afterPushBody.indexOf('await this.pushAfterSendImage'),
  'the targeted text must be the first post-contact message',
);
assert.doesNotMatch(platform, /使用本地岗位化兜底/);
assert.match(assistant, /greetingHint/);
assert.match(preference, /招呼语补充要求（个人可选）/);
assert.doesNotMatch(preference, /AI生成招呼语/);
assert.doesNotMatch(preference, /v-model="userStore\.user\.preference\.cgE"/);

const jobInfo = {jobName: '内容运营助理', companyName: '示例科技', postDescription: '负责资料整理和内容审核'};
assert.equal(
  extractGreetingText({data: {data: '您好，看到内容运营助理岗位，您提到的资料整理和内容审核与我的经历比较匹配。'}}),
  '您好，看到内容运营助理岗位，您提到的资料整理和内容审核与我的经历比较匹配。',
);
assert.equal(
  isUsableJobGreeting('刚看到贵公司的招聘信息，希望能与您有进一步的交流。不知道是否方便？', jobInfo),
  false,
);
assert.equal(
  isUsableJobGreeting('您好，看到内容运营助理岗位，您提到的资料整理和内容审核与我的经历比较匹配。', jobInfo),
  true,
);
assert.equal(
  isUsableJobGreeting('您好，对这个岗位比较感兴趣，希望进一步交流。', jobInfo),
  false,
);

console.log('greeting flow smoke: PASS');
