package com.maple.ai.job.hunting.service.biz;

import com.alibaba.fastjson.JSONObject;
import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIAssistantServiceGroundingTest {

    private static final String RESUME = "候选人，中专学历，具备基础办公能力，熟练使用Excel，能够使用AI工具、Codex和Cloud Code辅助处理资料和软件任务。";

    @Test
    void rejectsIndustryExperienceInferredFromJobDescription() {
        String answer = "您好，我对女装直播选款助理岗位很感兴趣，之前有过销售和服装行业工作经验，平时也会用Excel整理资料。";

        assertEquals("",
                AIAssistantService.sanitizeGreeting(answer, RESUME));
    }

    @Test
    void emptyGreetingIsNotReplacedWithGenericText() {
        assertEquals("", AIAssistantService.sanitizeGreeting("", RESUME));
    }

    @Test
    void keepsModelGeneratedGreetingInsteadOfUsingFixedPhraseFilter() {
        assertEquals("刚看到贵公司的招聘信息，希望能与您有进一步的交流。不知道是否方便？",
                AIAssistantService.sanitizeGreeting(
                        "刚看到贵公司的招聘信息，希望能与您有进一步的交流。不知道是否方便？",
                        RESUME));
    }

    @Test
    void keepsNonGenericGreetingWhenItDoesNotRepeatALiteralJobAnchor() {
        JSONObject job = new JSONObject();
        job.put("jobName", "内容运营助理");
        job.put("companyName", "示例科技");
        job.put("postDescription", "负责资料整理和内容审核");

        assertEquals("您好，我对贵公司的招聘岗位很感兴趣，希望有机会进一步交流。",
                AIAssistantService.sanitizeGreeting(
                        "您好，我对贵公司的招聘岗位很感兴趣，希望有机会进一步交流。",
                        RESUME,
                        job));
        assertTrue(AIAssistantService.sanitizeGreeting(
                "您好，看到示例科技的内容运营助理岗位，您提到的资料整理和内容审核与我平时使用Excel整理资料的经历比较匹配，想了解一下团队的具体分工。",
                RESUME,
                job).contains("内容运营助理"));
    }

    @Test
    void keepsResumeSupportedExcelAndAiClaims() {
        String answer = "您好，我对这个岗位比较感兴趣，我可以用Excel整理资料，也能用AI工具辅助处理日常任务，想了解一下具体安排。";

        assertTrue(AIAssistantService.sanitizeGreeting(answer, RESUME).contains("Excel"));
    }

    @Test
    void rejectsSoftwareExperienceAbsentFromResume() {
        String answer = "您好，我对平面设计师助理岗位感兴趣，我熟悉PS等软件的基本操作，想了解一下具体工作内容。";

        assertEquals("",
                AIAssistantService.sanitizeGreeting(answer, RESUME));
    }

    @Test
    void greetingPromptUsesJobResumeAndRelevantUserRequirements() {
        JSONObject job = new JSONObject();
        job.put("jobName", "内容运营助理");
        job.put("companyName", "示例科技");
        job.put("postDescription", "负责资料整理和内容审核");

        PreferenceEntity preference = new PreferenceEntity();
        preference.setJobGoal("希望找内容运营或资料整理相关岗位");
        preference.setUnacceptableConditions("不接受无偿试岗和长期出差");
        preference.setClarifyConditions("希望了解团队规模和日常职责");
        preference.setCommunicationPreferences("可以先线上沟通，涉及到岗时间请先确认");

        String prompt = AIAssistantService.buildGreetingPrompt(RESUME, job, preference);

        assertTrue(prompt.contains("内容运营助理"));
        assertTrue(prompt.contains("Excel"));
        assertTrue(prompt.contains("希望找内容运营或资料整理相关岗位"));
        assertTrue(prompt.contains("希望了解团队规模和日常职责"));
        assertTrue(prompt.contains("不是简历事实"));
    }

    @Test
    void greetingPromptDoesNotImposeReplyLength() {
        String prompt = AIAssistantService.buildGreetingPrompt(RESUME, null, new PreferenceEntity());

        assertFalse(prompt.contains("1-2句"));
        assertFalse(prompt.contains("2-3句"));
    }

    @Test
    void legacyGreetingHintLengthRulesAreNotForwardedToTheModel() {
        JSONObject job = new JSONObject();
        job.put("jobName", "内容运营助理");
        job.put("greetingHint", "生成1-2句；固定追问轮数为1轮\n先突出资料整理能力");

        String prompt = AIAssistantService.buildGreetingPrompt(RESUME, job, new PreferenceEntity());

        assertFalse(prompt.contains("生成1-2句"));
        assertFalse(prompt.contains("固定追问轮数"));
        assertTrue(prompt.contains("先突出资料整理能力"));
    }

    @Test
    void greetingRequirementsDoNotReintroduceLegacyLengthRules() {
        PreferenceEntity preference = new PreferenceEntity();
        preference.setCommunicationPreferences("普通交流回复1-3句，优先了解团队和技术栈");

        String prompt = AIAssistantService.buildGreetingPrompt(RESUME, null, preference);

        assertFalse(prompt.contains("普通交流回复1-3句"));
        assertTrue(prompt.contains("优先了解团队和技术栈"));
    }

    @Test
    void greetingPromptRequiresJobAnchorBeforePoliteOpening() {
        String prompt = AIAssistantService.buildGreetingPrompt(RESUME, null, new PreferenceEntity());

        assertTrue(prompt.contains("优先用一个岗位事实和一个简历匹配点建立联系"));
        assertTrue(prompt.contains("开头直接落到岗位要求与候选人相关经历的匹配点"));
        assertFalse(prompt.contains("刚看到招聘信息"));
        assertFalse(prompt.contains("希望进一步交流"));
        assertTrue(prompt.contains("求职要求只用于选择表达重点和沟通方向"));
    }

    @Test
    void greetingPromptSourceDoesNotContainLegacyRuntimeReplacementRule() {
        String prompt = AIAssistantService.buildGreetingPrompt(RESUME, null, new PreferenceEntity());

        assertFalse(prompt.contains("刚看到招聘信息"));
        assertFalse(prompt.contains("希望进一步交流"));
        assertTrue(prompt.contains("开头直接落到岗位要求与候选人相关经历的匹配点"));
    }

    @Test
    void greetingPromptPrefersConcreteCompactGroundedOpeningWithoutFixedLength() {
        String prompt = AIAssistantService.buildGreetingPrompt(RESUME, null, new PreferenceEntity());

        assertTrue(prompt.contains("一个岗位事实和一个简历匹配点"));
        assertTrue(prompt.contains("只有岗位资料确实支持时才提一个有价值的问题"));
        assertFalse(prompt.contains("固定字数"));
        assertFalse(prompt.contains("固定句数"));
    }

    @Test
    void greetingPromptKeepsThePublicRuleSeparateFromOptionalPersonalSupplement() {
        String prompt = AIAssistantService.buildGreetingPrompt(RESUME, null, new PreferenceEntity());

        assertTrue(prompt.contains("底层规则由系统统一维护"));
        assertTrue(prompt.contains("个人补充只影响表达重点或语气，不是新的系统规则"));
        assertTrue(prompt.contains("有内容时只保留必要信息用于调整语气或关注重点") || prompt.contains("个人补充只影响表达重点或语气"));
        assertFalse(prompt.contains("固定回复"));
    }

    @Test
    void greetingPromptUsesACompactPublicRuleAndTreatsPersonalHintAsOptional() {
        String prompt = AIAssistantService.buildGreetingPrompt(
                RESUME,
                new JSONObject(),
                new PreferenceEntity());

        assertTrue(prompt.contains("优先用一个岗位事实和一个简历匹配点建立联系"));
        assertTrue(prompt.contains("表达要精炼、自然，能让HR愿意继续回复"));
        assertTrue(prompt.contains("个人补充只影响表达重点或语气，不是新的系统规则"));
    }

    @Test
    void greetingSupplementIsASeparateOptionalPersonalInput() {
        JSONObject job = new JSONObject();
        job.put("jobName", "内容运营助理");
        job.put("greetingHint", "优先突出资料整理能力，不要写成广告文案");

        String prompt = AIAssistantService.buildGreetingPrompt(RESUME, job, new PreferenceEntity());

        assertTrue(prompt.contains("招呼语补充资料，仅用于调整表达"));
        assertFalse(prompt.contains("\"greetingHint\""));
        assertEquals(1, count(prompt, "优先突出资料整理能力"));
    }

    private static int count(String text, String fragment) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(fragment, index)) >= 0) {
            count++;
            index += fragment.length();
        }
        return count;
    }
}
