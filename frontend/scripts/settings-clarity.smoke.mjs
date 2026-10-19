import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {deferred, importHarness} from '../tests/helpers/resumeImportHarness.ts';

const panel = readFileSync(new URL('../src/components/ui/Panel.vue', import.meta.url), 'utf8');
const invitationExchange = readFileSync(new URL('../src/components/ui/InvitationExchange.vue', import.meta.url), 'utf8');
const betaDialog = readFileSync(new URL('../src/components/ui/Product.vue', import.meta.url), 'utf8');
const aiJob = readFileSync(new URL('../src/components/ui/AiJob.vue', import.meta.url), 'utf8');
const preference = readFileSync(new URL('../src/components/ui/Preference.vue', import.meta.url), 'utf8');
const preferenceDefaults = readFileSync(new URL('../src/stores/preferenceDefaults.ts', import.meta.url), 'utf8');
const promptConstants = readFileSync(new URL('../../backend/src/main/java/com/maple/ai/job/hunting/consts/AIPromptStrConstant.java', import.meta.url), 'utf8');
const chatService = readFileSync(new URL('../../backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedService.java', import.meta.url), 'utf8');
const preferenceTypes = readFileSync(new URL('../src/stores/types.ts', import.meta.url), 'utf8');
const remote = readFileSync(new URL('../src/stores/remote.ts', import.meta.url), 'utf8');
const serverPrompt = readFileSync(new URL('../../backend/src/main/java/com/maple/ai/job/hunting/service/ai/AbstractAIService.java', import.meta.url), 'utf8');
const serverController = readFileSync(new URL('../../backend/src/main/java/com/maple/ai/job/hunting/controller/UserAIConfigController.java', import.meta.url), 'utf8');
const serverPermissions = readFileSync(new URL('../../backend/src/main/java/com/maple/ai/job/hunting/config/ProductPermissionConfig.java', import.meta.url), 'utf8');

assert.doesNotMatch(panel, /AiConfig|模型微调|调试用户提示词|\/api\/user\/ai\/config\/debug/);
assert.doesNotMatch(serverPrompt, /UserAIConfigMapper|UserAIConfigDO|userPrompt|buildCommunicationPreference/);
assert.doesNotMatch(serverController, /DebugPromptVO|JobSeekerClonedResultVO|\/debug/);
assert.doesNotMatch(serverPermissions, /\/api\/user\/ai\/config\/debug|MODEL_FINE_TUNING/);
assert.doesNotMatch(preference, /预设问题|轻度异议挽留|延迟回复|每轮交流邮件|高意向邮件/);
assert.doesNotMatch(promptConstants, /预设问题|开启挽留|用户补充的挽留偏好/);
assert.doesNotMatch(chatService, /hitRfAndGet|isRfE\(\)|isPpE\(\)|isErmE\(\)|isCrE\(\)/);
assert.doesNotMatch(preferenceTypes, /ppE|rfE|ermE|drE|crE|srT/);
assert.doesNotMatch(betaDialog, /产品列表|更多产品|优惠码|generate\/order\/group|支付宝|二维码|SSEClient/);
assert.match(betaDialog, /账号会自动创建并开通内测权限/);
assert.match(betaDialog, /无需导入简历；开始投递或开启 AI 坐席前，请先上传本人附件简历并点击“导入简历”/);
assert.match(aiJob, /完成后才能开始投递或开启 AI 坐席/);
assert.doesNotMatch(betaDialog, /请输入内测激活码|激活内测|\/api\/beta\/activate/);

assert.match(invitationExchange, /\/api\/beta\/access/);
assert.match(invitationExchange, /内测有效至/);
assert.match(invitationExchange, /formatDate/);

assert.match(aiJob, /const resumeImportStatus = ref\(userStore\.user\.resumeId \? '成功导入简历' : '未成功导入简历'\);/);
assert.doesNotMatch(aiJob, /const resumeImportStatus = computed\(\(\) => userStore\.user\.resumeId/);
assert.match(aiJob, /let resumeImportAttempted = false;/);
assert.match(aiJob, /watch\(\(\) => userStore\.user\.resumeId,\s*\(resumeId\) => \{\s*if \(!resumeImportAttempted\) \{\s*resumeImportStatus\.value = resumeId \? '成功导入简历' : '未成功导入简历';/);
assert.match(aiJob, /成功导入简历/);
assert.match(aiJob, /未成功导入简历/);
assert.match(aiJob, /userStore\.user\.resumeId\s*=\s*resumeId;\s*resumeImportStatus\.value = '成功导入简历';/);
const identityWait = deferred();
const importing = importHarness();
importing.identityWith(() => identityWait.promise);
const pendingImport = importing.run();
await new Promise(resolve => setImmediate(resolve));
assert.equal(importing.status.value, '成功导入简历', 'waiting for identity must preserve the saved-state badge');
assert.equal(importing.lookups.length, 0, 'identity must be ready before looking up attachments');
importing.respondWith(async () => ({code: 2003, message: '身份校验失败'}));
identityWait.resolve({accountId: importing.page.uid, token: importing.page.token});
await pendingImport;
assert.equal(importing.userStore.user.resumeId, 'old-resume', 'identity rejection must preserve the existing resume');
assert.equal(importing.status.value, '未成功导入简历', 'a rejected save must not be shown as a new successful import');
assert.equal(importing.notices.length, 1, 'identity rejection must be reported once');
assert.match(importing.notices[0].message, /身份/);

assert.match(preference, /系统底层规则/);
assert.match(preference, /求职要求/);
assert.match(preference, /营销推广类消息自动回复/);
assert.match(preference, /默认关闭/);
assert.match(preferenceTypes, /marketingReplyE/);
assert.doesNotMatch(preference, /未在【产品列表】中购买【ai过滤】产品请勿开启/);
assert.match(preference, /applyPersonalPreferenceDefaults\(preference\)/);
assert.match(preferenceDefaults, /优先结合我的简历经历、技能和岗位职责判断匹配度/);
assert.match(preferenceDefaults, /薪资范围与构成、地点或办公方式、工时与加班/);
assert.match(preferenceDefaults, /礼貌、自然、简洁/);
assert.match(preference, /label="薪资范围"/);
assert.match(preference, /填写参考：8-12、12以上或20以下/);
assert.match(preference, /请填写如 8-12、12以上或20以下的薪资范围/);

for (const label of [
    '我想找什么工作',
    '哪些情况我不能接受',
    '信息不全时优先问什么',
    '跟 HR 沟通时按什么方式',
    '我不希望 AI 回复哪些推广',
]) {
    assert.ok(preference.includes(`label="${label}"`), `missing field label: ${label}`);
}

for (const example of ['最低薪资是多少', '优先哪个城市', '能否接受微信沟通', '哪些时间方便面试']) {
    assert.ok(preference.includes(example), `missing help example: ${example}`);
    assert.ok(!preferenceDefaults.includes(example), `help example must not be saved as a preference: ${example}`);
}

assert.match(preference, /promotionReplyPolicy/);
assert.match(preferenceTypes, /promotionReplyPolicy\?: string/);
assert.match(remote, /marketingReplyE\s*=\s*preference\.marketingReplyE\s*\?\?\s*false/);
assert.match(preferenceDefaults, /不因岗位名称或单个词被过滤/);
assert.match(preference, /薪资、地点、联系方式、合同、加班和出差按上方的个人偏好判断/);

console.log('settings clarity smoke: PASS');
