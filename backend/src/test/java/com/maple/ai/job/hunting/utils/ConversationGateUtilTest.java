package com.maple.ai.job.hunting.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void onlyEmptyAndProtocolNoiseAreSilentlySuppressedBeforeAi() {
        assertFalse(ConversationGateUtil.shouldDelegateToAi("   "));
        assertFalse(ConversationGateUtil.shouldDelegateToAi("\b\u0001\u0012\u00031.3\u001ahttps://img.bosszhipin.com/beijin/upload/avatar/a.webp\u0000\u0001\u0002\u0003\u0004"));
    }

}
