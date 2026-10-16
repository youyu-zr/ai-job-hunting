import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const aiJob = read('../src/components/ui/AiJob.vue');
const bossMessage = read('../src/components/ui/BossMessage.vue');
const platform = read('../src/platform/platform.ts');
const preference = read('../src/components/ui/Preference.vue');
const conversationGate = read('../../backend/src/main/java/com/maple/ai/job/hunting/utils/ConversationGateUtil.java');
const clonedService = read('../../backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedService.java');

test('single-run delivery limit defaults to five and remains editable', () => {
    assert.match(platform, /private _selfDefPushCountLimit = 5/, 'default limit must be five');
    assert.match(aiJob, /<el-input-number[^>]*:min="1"[^>]*:max="100"/, 'limit input must allow 1-100');
});

test('delivery and enabling the AI seat require an imported resume', () => {
    assert.match(aiJob, /const requireImportedResume = \(\) =>/, 'shared resume preflight must exist');
    assert.match(aiJob, /请先在 BOSS 上传本人附件简历，再点击“导入简历”/, 'missing-resume prompt must explain the next step');
    const startPush = aiJob.slice(aiJob.indexOf('const startPush ='), aiJob.indexOf('const pausePush ='));
    assert.match(startPush, /if \(!requireImportedResume\(\)\)\s*\{\s*return;\s*\}/, 'delivery must stop before starting');
    const seatHandlerStart = aiJob.indexOf('const handlerAISeatStatusChange =');
    const seatHandlerEnd = aiJob.indexOf('// --------------------------------------------------事件处理', seatHandlerStart);
    assert.ok(seatHandlerStart >= 0 && seatHandlerEnd > seatHandlerStart, 'seat handler must be readable');
    assert.match(aiJob.slice(seatHandlerStart, seatHandlerEnd), /if \(val && !requireImportedResume\(\)\)\s*\{\s*return;\s*\}/,
        'seat can still be turned off without a resume');
});

test('a server-side resume gate rejection is explained to the user', () => {
    const seatHandlerStart = aiJob.indexOf('const handlerAISeatStatusChange =');
    const seatHandlerEnd = aiJob.indexOf('// --------------------------------------------------事件处理', seatHandlerStart);
    const handler = aiJob.slice(seatHandlerStart, seatHandlerEnd);
    assert.match(handler, /catch\s*\(\s*\(error:\s*any\)\s*=>\s*\{[\s\S]*ElMessage\(/,
        'server rejection must not silently roll back the switch');
    assert.match(handler, /请先导入本人简历，再开启 AI 坐席/,
        'missing-resume rejection must explain the next step');
});

test('chat page has no multi-contact message sending entry', () => {
    assert.doesNotMatch(bossMessage, /批量发送消息|BATCH_MANUAL|batch-send/, 'batch message controls must be removed');
    assert.match(bossMessage, /恢复当前会话/, 'manual resume control must remain');
});

test('the preference toggle is explicitly limited to high-interest handoff', () => {
    assert.match(preference, /高意向时暂停AI坐席/, 'toggle name must describe its actual scope');
    assert.doesNotMatch(preference, /关键事项暂停AI坐席/, 'toggle must not imply it controls deterministic gates');
    assert.match(preference, /联系方式[\s\S]*面试[\s\S]*薪资[\s\S]*不受.*开关/,
        'the independent hard gates must be described');
});

test('only concrete commitments pause the current job session before reply generation', () => {
    const triageIndex = clonedService.indexOf('messageTriageService.classify(');
    const confirmationActionIndex = clonedService.indexOf(
        'if (triage.getAction() == Action.NEEDS_USER_CONFIRMATION)',
    );
    const commitmentGateIndex = clonedService.indexOf(
        'if (isConcreteCurrentCommitment(jobSeekerClonedParam.getQuestion()))',
    );
    const aiCallIndex = clonedService.indexOf('aiServiceFacade.sessionChat');
    assert.ok(triageIndex >= 0 && triageIndex < commitmentGateIndex,
        'server AI must classify HR meaning before applying personal-commitment rules');
    assert.ok(commitmentGateIndex < aiCallIndex,
        'concrete personal commitments must stop before reply generation');
    assert.ok(triageIndex < confirmationActionIndex && confirmationActionIndex < aiCallIndex,
        'the authoritative confirmation action must stop before reply generation');
    assert.match(clonedService.slice(confirmationActionIndex, commitmentGateIndex),
        /return pauseForUserConfirmation\(jobKey\)/);
    assert.match(clonedService.slice(commitmentGateIndex, aiCallIndex),
        /return pauseForUserConfirmation\(jobKey\)/);
    const pauseHelperStart = clonedService.indexOf(
        'private JobSeekerClonedResultVO pauseForUserConfirmation(String jobKey)',
    );
    const pauseHelperEnd = clonedService.indexOf(
        'private JobSeekerClonedResultVO retryableProcessingFailure()', pauseHelperStart,
    );
    assert.ok(pauseHelperStart >= 0 && pauseHelperEnd > pauseHelperStart);
    const pauseHelper = clonedService.slice(pauseHelperStart, pauseHelperEnd);
    assert.match(pauseHelper, /if \(!msgSessionService\.trySetMsgSessionStop\(jobKey, true\)\)/);
    assert.match(pauseHelper, /return retryableProcessingFailure\(\)/,
        'failed pause persistence must remain retryable');
    assert.match(pauseHelper, /"USER_CONFIRMATION_REQUIRED"/);
    assert.doesNotMatch(clonedService.slice(triageIndex, aiCallIndex),
        /humanHandoffCategory/,
        'salary, location, and contact topics must not use the retired keyword pause gate');
});
