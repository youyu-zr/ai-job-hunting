import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const panel = readFileSync(new URL('../src/components/ui/Panel.vue', import.meta.url), 'utf8');
const invitationExchange = readFileSync(new URL('../src/components/ui/InvitationExchange.vue', import.meta.url), 'utf8');
const betaDialog = readFileSync(new URL('../src/components/ui/Product.vue', import.meta.url), 'utf8');
const aiJob = readFileSync(new URL('../src/components/ui/AiJob.vue', import.meta.url), 'utf8');
const preference = readFileSync(new URL('../src/components/ui/Preference.vue', import.meta.url), 'utf8');
const preferenceDefaults = readFileSync(new URL('../src/stores/preferenceDefaults.ts', import.meta.url), 'utf8');
const promptConstants = readFileSync(new URL('../../backend/src/main/java/com/maple/ai/job/hunting/consts/AIPromptStrConstant.java', import.meta.url), 'utf8');
const chatService = readFileSync(new URL('../../backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedService.java', import.meta.url), 'utf8');
const preferenceTypes = readFileSync(new URL('../src/stores/types.ts', import.meta.url), 'utf8');
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
assert.match(aiJob, /const handlerImport = async \(\) => \{\s*resumeImportAttempted = true;\s*resumeImportStatus\.value = '未成功导入简历';/);
assert.match(aiJob, /if \(importResp\.data\.code !== 200\)[\s\S]*?return;[\s\S]*?userStore\.user\.resumeId\s*=\s*resumeId/);
assert.match(aiJob, /userStore\.user\.resumeId\s*=\s*resumeId;\s*resumeImportStatus\.value = '成功导入简历';/);
assert.match(aiJob, /catch \(error: any\) \{[\s\S]*?resumeImportStatus\.value = '未成功导入简历';[\s\S]*?导入简历失败/);

assert.match(preference, /系统底层规则/);
assert.match(preference, /求职要求/);
assert.doesNotMatch(preference, /未在【产品列表】中购买【ai过滤】产品请勿开启/);
assert.match(preference, /applyPersonalPreferenceDefaults\(preference\)/);
assert.match(preferenceDefaults, /优先结合我的简历经历、技能和当前岗位职责判断匹配度/);
assert.match(preferenceDefaults, /薪资构成、实际工作内容、办公地点或方式/);
assert.match(preferenceDefaults, /保持礼貌、自然、简洁/);
assert.match(preference, /label="薪资范围"/);
assert.match(preference, /placeholder="例如 8-12、12以上或20以下"/);
assert.match(preference, /请填写如 8-12、12以上或20以下的薪资范围/);

console.log('settings clarity smoke: PASS');
