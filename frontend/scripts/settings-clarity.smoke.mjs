import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const panel = readFileSync(new URL('../src/components/ui/Panel.vue', import.meta.url), 'utf8');
const invitationExchange = readFileSync(new URL('../src/components/ui/InvitationExchange.vue', import.meta.url), 'utf8');
const betaDialog = readFileSync(new URL('../src/components/ui/Product.vue', import.meta.url), 'utf8');
const aiJob = readFileSync(new URL('../src/components/ui/AiJob.vue', import.meta.url), 'utf8');
const preference = readFileSync(new URL('../src/components/ui/Preference.vue', import.meta.url), 'utf8');
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
assert.match(betaDialog, /成功导入简历/);
assert.match(betaDialog, /自动开通内测权限/);
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
assert.match(preference, /目标岗位为 Java 后端或 AI 应用开发/);
assert.match(preference, /不接受纯佣金、长期夜班、频繁出差或长期驻场/);
assert.match(preference, /工作内容占比、团队和汇报对象/);
assert.match(preference, /优先在 BOSS 在线沟通/);
assert.match(preference, /label="薪资范围"/);
assert.match(preference, /placeholder="例如 8-12、12以上或20以下"/);
assert.match(preference, /8-12 表示区间，12以上表示最低要求，20以下表示最高要求/);

console.log('settings clarity smoke: PASS');
