package com.maple.ai.job.hunting.utils;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationGateUtilTest {

    @Test
    void refusalAndSystemMessagesMustNotTriggerHighInterest() {
        assertTrue(ConversationGateUtil.isRefusalOrSystemMessage(
                "感谢您的投递，但很遗憾，我们正在寻找与您的技能和经验不完全匹配的候选人"));
        assertTrue(ConversationGateUtil.isRefusalOrSystemMessage(
                "对方拒绝了您的发送请求"));
        assertFalse(ConversationGateUtil.isRefusalOrSystemMessage(
                "方便明天下午来面试吗？"));
    }

    @Test
    void submissionAcknowledgementIsSilentButResumeRequestRemainsActionable() {
        assertTrue(ConversationGateUtil.isConversationClosingMessage(
                "感谢您的提交。后续事宜请耐心等待团队评估即可。谢谢您的配合。"));
        assertTrue(ConversationGateUtil.isConversationClosingMessage(
                "感谢您的配合。后续事宜请耐心等待即可。祝好。"));
        assertFalse(ConversationGateUtil.isConversationClosingMessage(
                "感谢您的配合，请通过平台简历投递功能提交即可，后续事宜请耐心等待评估。谢谢。"));
        assertFalse(ConversationGateUtil.isConversationClosingMessage(
                "岗位主要负责资料整理和Excel记录，方便了解一下吗？"));
    }

    @Test
    void contactExchangeRequiresHumanGate() {
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("方便交换微信吗？"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("我想要一个您的电话号码，您是否同意"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("方便换个电话吗？"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("可以留个微信，后面沟通吗？"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("您的电话多少？"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("加我微信：13800138000"));
        assertFalse(ConversationGateUtil.isContactExchangeQuestion("方便发一份简历过来吗？"));
    }

    @Test
    void ordinaryContactTopicsStayInAiConversation() {
        assertFalse(ConversationGateUtil.isContactExchangeQuestion("方便电话沟通吗？"));
        assertFalse(ConversationGateUtil.isContactExchangeQuestion("后面加微信方便吗？"));
        assertFalse(ConversationGateUtil.isContactExchangeQuestion("后续可以通过电话了解一下岗位吗？"));
        assertFalse(ConversationGateUtil.isContactExchangeQuestion("微信沟通也可以，先介绍一下团队吧。"));
    }

    @Test
    void actualContactValuesStillRequireHumanGate() {
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("我的手机号是13800138000"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("微信号：zhaorui_123"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("微信号是zhaorui_123"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("微信号为zhaorui_123"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("微信：zhaorui_123"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("电话：13800138000"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("请把您的联系方式发给我"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("可以把微信给我吗？"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("方便提供电话吗？"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("电话多少？"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("可以把你的微信发我吗？"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("加我微信，后面具体沟通"));
    }

    @Test
    void contactProtocolMarkersStillRequireHumanGate() {
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("联系方式卡片 &lt;/phone&gt;"));
        assertTrue(ConversationGateUtil.isContactExchangeQuestion("联系方式卡片 </copy>"));
    }

    @Test
    void interviewCardsAndSchedulingTextAreRecognized() {
        assertTrue(ConversationGateUtil.isInterviewSchedulingQuestion(
                "面试邀约：明天下午三点到公司参加初试"));
        assertTrue(ConversationGateUtil.isInterviewSchedulingQuestion(
                "面试卡片：请点击接受邀约"));
        assertFalse(ConversationGateUtil.isInterviewSchedulingQuestion(
                "感谢您的投递，暂不匹配"));
        assertTrue(ConversationGateUtil.isExplicitInterviewArrangementQuestion(
                "方便明天下午三点来公司面试吗？"));
        assertFalse(ConversationGateUtil.isExplicitInterviewArrangementQuestion(
                "方便过来面试吗？"));
    }

    @Test
    void locationDecisionCardsRequireHumanGateAndDoNotGoToAi() {
        String rawLocationCard = "\b\u0001{\"jobLocationRefactor\":true,\"locationDesc\":\"上海浦东新区康桥地铁站附近\",\"addressNum\":\"康桥路\"} 暂不考虑 可以接受 您是否接受此工作地点? bosszp://bosszhipin.app/openwith?type=sendaction";
        assertTrue(ConversationGateUtil.isLocationDecisionQuestion(rawLocationCard));
        assertFalse(ConversationGateUtil.isNoisyProtocolPayload(rawLocationCard));
        assertFalse(ConversationGateUtil.isLocationDecisionQuestion("办公地点在上海浦东，能接受吗？"));
    }

    @Test
    void binaryRichCardsWithoutHumanDecisionAreSilentNoise() {
        String rawStickerOrCard = "\b\u0001\u0012\u00031.3\u001ahttps://img.bosszhipin.com/beijin/upload/avatar/a.webp\u0000\u0001\u0002\u0003\u0004\u0005";
        assertTrue(ConversationGateUtil.isNoisyProtocolPayload(rawStickerOrCard));
    }

    @Test
    void substantiveHrTopicsMustReachAiInsteadOfBeingFixedGated() {
        assertTrue(ConversationGateUtil.shouldDelegateToAi("方便交换微信吗？"));
        assertTrue(ConversationGateUtil.shouldDelegateToAi("明天下午三点方便线上面试吗？"));
        assertTrue(ConversationGateUtil.shouldDelegateToAi("这个岗位暂时不太合适，谢谢。"));
        assertTrue(ConversationGateUtil.shouldDelegateToAi("办公地点在上海浦东，能接受吗？"));
    }

    @Test
    void onlyConcreteInterviewsAndExplicitContractCommitmentsRequireHandoff() {
        assertEquals("", handoffCategory("方便交换微信吗？"));
        assertEquals("INTERVIEW", handoffCategory("方便明天下午三点来公司面试吗？"));
        assertEquals("", handoffCategory("办公地点在上海浦东，能接受吗？"));
        assertEquals("", handoffCategory("这个薪资可以接受吗？"));
        assertEquals("", handoffCategory("你最快什么时候可以到岗？"));
        assertEquals("INTERVIEW", handoffCategory("请确认是否接受并签署这份合同。"));
        assertEquals("", handoffCategory("合同期限是三年，可以先了解一下吗？"));

        assertEquals("", handoffCategory("方便电话沟通一下岗位职责吗？"));
        assertEquals("", handoffCategory("薪资结构是底薪加绩效，具体面试流程有三轮。"));
        assertEquals("", handoffCategory("办公地点在上海浦东，团队在康桥地铁站附近。"));
    }

    @Test
    void handoffDoesNotTriggerOnDescriptionsQuestionsOrNegatedConditions() {
        assertEquals("", handoffCategory("工作地点不接受远程办公，统一上海现场办公。"));
        assertEquals("", handoffCategory("我们面试流程是三轮，周一介绍团队，方便电话聊岗位职责吗？"));
        assertEquals("", handoffCategory("请问您的期望薪资是多少？"));
        assertEquals("", handoffCategory("转发招聘广告：薪资可接受，扫码进群领取资料。"));
        assertEquals("", handoffCategory("不好意思，岗位不太匹配，暂时不安排面试了。"));
    }

    @Test
    void handoffRecognizesSecondRoundAndExplicitCalendarDates() {
        assertEquals("INTERVIEW", handoffCategory("周四14:00二面，请回复确认。"));
        assertEquals("START_DATE", handoffCategory("恭喜录用，请于10月20日报到。"));
    }

    @Test
    void onlyEmptyAndProtocolNoiseAreSilentlySuppressedBeforeAi() {
        assertFalse(ConversationGateUtil.shouldDelegateToAi("   "));
        assertFalse(ConversationGateUtil.shouldDelegateToAi("\b\u0001\u0012\u00031.3\u001ahttps://img.bosszhipin.com/beijin/upload/avatar/a.webp\u0000\u0001\u0002\u0003\u0004"));
    }

    @Test
    void promotionalCtasAreSilentButOrdinaryPromotionJobsRemainConversational() {
        assertTrue(ConversationGateUtil.isPromotionalMessage("扫码进群领取资料，注册后安排推广任务"));
        assertTrue(ConversationGateUtil.isPromotionalMessage("扫码进群获取资料"));
        assertTrue(ConversationGateUtil.isPromotionalMessage("先交费培训后上岗，邀请好友返佣"));
        assertTrue(ConversationGateUtil.isPromotionalMessage("课程顾问岗位，添加微信领取课程资料"));
        assertFalse(ConversationGateUtil.isPromotionalMessage("我们招聘市场推广专员，负责品牌内容和活动执行"));
        assertFalse(ConversationGateUtil.isPromotionalMessage("岗位负责推广公司产品，正常底薪加绩效，不要求付费"));
        assertFalse(ConversationGateUtil.isPromotionalMessage("课程顾问工作职责：添加微信维护学员日常沟通"));
        assertFalse(ConversationGateUtil.isPromotionalMessage("岗位不涉及推广和扫码引流，正常底薪加绩效"));
    }

    @Test
    void hardPromotionalRisksRemainSilentEvenIfOptionalMarketingRepliesAreEnabled() {
        assertTrue(ConversationGateUtil.isHardPromotionalRisk("先充值成为代理，邀请好友返佣"));
        assertTrue(ConversationGateUtil.isHardPromotionalRisk("培训贷、押金和刷单任务"));
        assertTrue(ConversationGateUtil.isHardPromotionalRisk("缴纳入会费后开始接单"));
        assertTrue(ConversationGateUtil.isHardPromotionalRisk("加入传销团队，扫码了解"));
        assertTrue(ConversationGateUtil.isHardPromotionalRisk("需要购买账号后开始工作"));
        assertTrue(ConversationGateUtil.isHardPromotionalRisk("先交培训费和设备费"));
        assertTrue(ConversationGateUtil.isHardPromotionalRisk("岗位不涉及培训贷另有押金"));
        assertFalse(ConversationGateUtil.isHardPromotionalRisk("我们招聘市场推广专员，负责品牌内容和活动执行"));
        assertFalse(ConversationGateUtil.isHardPromotionalRisk("招聘售后客服，主要处理用户充值不到账和退款咨询"));
        assertFalse(ConversationGateUtil.isHardPromotionalRisk("岗位不涉及收费培训，不要求缴费"));
        assertFalse(ConversationGateUtil.isHardPromotionalRisk("招聘平台工作人员负责核验并退还历史入会费"));
        assertFalse(ConversationGateUtil.isHardPromotionalRisk(
                "岗位不要求求职者缴纳入会费，客服负责处理缴费和退款咨询"));
        assertFalse(ConversationGateUtil.isHardPromotionalRisk(
                "无需求职者缴纳入会费，客服负责处理用户缴费和退款咨询"));
        assertFalse(ConversationGateUtil.isHardPromotionalRisk("岗位不涉及传销，也不需要购买账号"));
        assertFalse(ConversationGateUtil.isPromotionalMessage(
                "我们招聘市场推广专员，日常工作要扫码签到、注册门店客户资料"));
        assertFalse(ConversationGateUtil.isPromotionalMessage(
                "岗位职责包括推广活动，扫码进群后每日签到和录入门店客户资料"));
        assertFalse(ConversationGateUtil.isPromotionalMessage(
                "岗位职责：负责课程推广，指导用户注册后完成门店资料录入"));
        assertTrue(ConversationGateUtil.isPromotionalMessage(
                "岗位职责：推广课程，请扫码进群领取资料"));
    }

    private String handoffCategory(String question) {
        Method method;
        try {
            method = ConversationGateUtil.class.getMethod("humanHandoffCategory", String.class);
        } catch (NoSuchMethodException e) {
            assertNotNull(null, "humanHandoffCategory must classify explicit user decisions");
            return "";
        }
        try {
            return (String) method.invoke(null, question);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

}
