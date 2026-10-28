package com.maple.ai.job.hunting.utils;

import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundCommitmentGuardTest {

    private final PreferenceEntity defaults = new PreferenceEntity();

    @Test
    void holdsAnAnswerThatPromisesAConcreteInterviewTime() {
        assertEquals(Optional.of(StopEventPolicy.Event.INTERVIEW_TIME),
                OutboundCommitmentGuard.find("好的，明天下午3点我准时到公司面试。", defaults));
    }

    @Test
    void holdsAnAnswerThatAcceptsAnOfferOrASalaryNumber() {
        assertEquals(Optional.of(StopEventPolicy.Event.OFFER),
                OutboundCommitmentGuard.find("我接受这个offer，谢谢！", defaults));
        assertEquals(Optional.of(StopEventPolicy.Event.SALARY_DECISION),
                OutboundCommitmentGuard.find("月薪8K我可以接受。", defaults));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "可以的，今天下午三点我到公司",
            "好的，周三上午我准时到"
    })
    void holdsTimePromisesWrittenInOtherWords(String answer) {
        assertEquals(Optional.of(StopEventPolicy.Event.INTERVIEW_TIME), OutboundCommitmentGuard.find(answer, defaults));
    }

    @Test
    void holdsJoiningWithoutTheWordOffer() {
        assertEquals(Optional.of(StopEventPolicy.Event.OFFER),
                OutboundCommitmentGuard.find("太好了，我很愿意加入贵司，下周就能来。", defaults));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "面试时间我需要和现公司确认，明天给您答复。",
            "面试时间我确认一下，稍后回复您。",
            "这个offer我需要考虑一下。",
            "我期望薪资是10-15K，想了解下构成。",
            "您好，我对这个岗位很感兴趣，方便介绍下工作内容吗？"
    })
    void nonCommittalAnswersPass(String answer) {
        assertTrue(OutboundCommitmentGuard.find(answer, defaults).isEmpty());
    }

    @Test
    void choosingAllowLetsTheCommitmentThrough() {
        PreferenceEntity p = new PreferenceEntity();
        p.setStopInterview("ALLOW");
        assertTrue(OutboundCommitmentGuard.find("好的，明天下午3点我准时到公司面试。", p).isEmpty());
    }

    @Test
    void acceptingAWrittenDealbreakerIsHeldButRefusingItIsNot() {
        PreferenceEntity p = new PreferenceEntity();
        p.setDealbreakers(List.of("单休"));
        assertEquals(Optional.of(StopEventPolicy.Event.DEALBREAKER),
                OutboundCommitmentGuard.find("单休也没问题，可以接受。", p));
        assertTrue(OutboundCommitmentGuard.find("抱歉，单休我不能接受。", p).isEmpty());
        assertTrue(OutboundCommitmentGuard.find("请问是单休吗？双休可以吗？", p).isEmpty());
        PreferenceEntity night = new PreferenceEntity();
        night.setDealbreakers(List.of("夜班"));
        assertTrue(OutboundCommitmentGuard.find("岗位内容没问题，想确认下是否有夜班？", night).isEmpty());
    }
}
