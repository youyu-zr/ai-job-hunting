package com.maple.ai.job.hunting.utils;

import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopEventPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "入职前需要先交500元培训费, SCAM_OR_OFF_PLATFORM",
            "麻烦把身份证正反面发我一下, SCAM_OR_OFF_PLATFORM",
            "下载我们的APP注册一下, SCAM_OR_OFF_PLATFORM",
            "Offer已经发你了，确认下是否接受, OFFER",
            "明天下午3点来公司面试可以吗, INTERVIEW_TIME",
            "薪资给到8K，可以接受吗, SALARY_DECISION",
            "方便加个微信吗, CONTACT",
            "方便的话打个电话聊, CONTACT",
            "请问结婚了吗, PERSONAL_INFO",
            "你目前薪资多少, PERSONAL_INFO",
            "没问题，培训费500元入职时交, SCAM_OR_OFF_PLATFORM",
            "不过要先交押金, SCAM_OR_OFF_PLATFORM",
            "无锡基地需交押金500, SCAM_OR_OFF_PLATFORM",
            "我们给到18k能接受吗, SALARY_DECISION",
            "劳动合同发你了，没问题就签一下, OFFER",
            "[语音], UNREADABLE",
            "先做一下这个笔试, UNREADABLE"
    })
    void detectsTheEventThatNeedsTheUser(String question, StopEventPolicy.Event expected) {
        assertEquals(Optional.of(expected), StopEventPolicy.detect(question));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "我们不收任何培训费，放心",
            "无押金，包住宿",
            "你好，对这个岗位感兴趣吗",
            "工作地点在上海，能接受吗",
            "期望薪资是多少",
            "我们不用先交任何费用",
            "差旅由公司垫付",
            "我们做app开发，需要熟悉软件安装部署",
            "五险一金含生育保险",
            "可解决北京户口",
            "流程是一轮笔试两轮面试",
            "我们没有笔试"
    })
    void ordinaryOrReassuringMessagesAreNotEvents(String question) {
        assertTrue(StopEventPolicy.detect(question).isEmpty());
    }

    @Test
    void aMessageWithSeveralEventsUsesTheStrictestSetting() {
        PreferenceEntity p = new PreferenceEntity();
        p.setStopInterview("ALLOW");
        var events = StopEventPolicy.detectAll("明天下午2点面试，薪资我们给15k能接受吗");
        assertEquals(List.of(StopEventPolicy.Event.INTERVIEW_TIME, StopEventPolicy.Event.SALARY_DECISION), events);
        assertEquals(Optional.of(StopEventPolicy.Event.SALARY_DECISION), StopEventPolicy.strictest(events, p));
    }

    @Test
    void defaultsPauseForCommitmentsAndLetAiSendTheResume() {
        PreferenceEntity p = new PreferenceEntity();
        assertEquals(StopEventPolicy.Policy.PAUSE, StopEventPolicy.policyFor(StopEventPolicy.Event.OFFER, p));
        assertEquals(StopEventPolicy.Policy.PAUSE, StopEventPolicy.policyFor(StopEventPolicy.Event.INTERVIEW_TIME, p));
        assertEquals(StopEventPolicy.Policy.PAUSE, StopEventPolicy.policyFor(StopEventPolicy.Event.SALARY_DECISION, p));
        assertEquals(StopEventPolicy.Policy.PAUSE, StopEventPolicy.policyFor(StopEventPolicy.Event.CONTACT, p));
        assertEquals(StopEventPolicy.Policy.ALLOW, StopEventPolicy.policyFor(StopEventPolicy.Event.RESUME, p));
        assertEquals(StopEventPolicy.Policy.PAUSE, StopEventPolicy.policyFor(StopEventPolicy.Event.CONTACT, null));
    }

    @Test
    void legacyContactShareSwitchKeepsLettingAiHandleContacts() {
        PreferenceEntity p = new PreferenceEntity();
        p.setContactShareE(true);
        assertEquals(StopEventPolicy.Policy.ALLOW, StopEventPolicy.policyFor(StopEventPolicy.Event.CONTACT, p));
        p.setStopContact("PAUSE");
        assertEquals(StopEventPolicy.Policy.PAUSE, StopEventPolicy.policyFor(StopEventPolicy.Event.CONTACT, p));
    }

    @Test
    void scamUnreadableAndDealbreakerCannotBeHandedToAi() {
        PreferenceEntity p = new PreferenceEntity();
        p.setStopPersonal("ALLOW");
        assertEquals(StopEventPolicy.Policy.ALLOW, StopEventPolicy.policyFor(StopEventPolicy.Event.PERSONAL_INFO, p));
        for (StopEventPolicy.Event e : List.of(StopEventPolicy.Event.SCAM_OR_OFF_PLATFORM,
                StopEventPolicy.Event.UNREADABLE, StopEventPolicy.Event.DEALBREAKER)) {
            assertEquals(StopEventPolicy.Policy.PAUSE, StopEventPolicy.policyFor(e, p));
        }
    }

    @Test
    void unknownStoredValueFallsBackToTheDefault() {
        PreferenceEntity p = new PreferenceEntity();
        p.setStopOffer("whatever");
        assertEquals(StopEventPolicy.Policy.PAUSE, StopEventPolicy.policyFor(StopEventPolicy.Event.OFFER, p));
        p.setStopOffer("decline");
        assertEquals(StopEventPolicy.Policy.DECLINE, StopEventPolicy.policyFor(StopEventPolicy.Event.OFFER, p));
    }

    @Test
    void scamGetsNoHoldReplyAndOthersGetANonCommittalOne() {
        assertEquals("", StopEventPolicy.holdText(StopEventPolicy.Event.SCAM_OR_OFF_PLATFORM));
        String hold = StopEventPolicy.holdText(StopEventPolicy.Event.OFFER);
        assertFalse(hold.contains("可以") || hold.contains("接受") || hold.contains("好的"));
    }

    @Test
    void everyEventHasALabel() {
        for (StopEventPolicy.Event e : StopEventPolicy.Event.values()) {
            assertFalse(StopEventPolicy.label(e).isBlank());
        }
    }
}
