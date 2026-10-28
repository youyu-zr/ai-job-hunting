package com.maple.ai.job.hunting.utils;

import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredPreferenceTextTest {

    private PreferenceEntity filled() {
        PreferenceEntity p = new PreferenceEntity();
        p.setHomeCity("上海");
        p.setHomeArea("浦东 张江");
        p.setCommuteMaxMinutes(60);
        p.setSalaryMinK(12);
        p.setSalaryMaxK(18);
        p.setSalaryFloorK(10);
        p.setAvoidTags(List.of("电话销售"));
        p.setDealbreakers(List.of("单休"));
        p.setBlockedCompanies(List.of("某某科技"));
        p.setArrivalTime("两周内");
        return p;
    }

    @Test
    void screeningSeesTheFloorAndBlockedCompanies() {
        String text = StructuredPreferenceText.forScreening(filled());
        assertTrue(text.contains("上海 浦东 张江"));
        assertTrue(text.contains("60 分钟"));
        assertTrue(text.contains("12-18K"));
        assertTrue(text.contains("10K"));
        assertTrue(text.contains("某某科技"));
    }

    @Test
    void replyNeverSeesTheFloorOrBlockedCompaniesAndIsToldNotToAgreeToNumbers() {
        String text = StructuredPreferenceText.forReply(filled());
        assertTrue(text.contains("12-18K"));
        assertFalse(text.contains("10K"), "the floor must never reach the chat model");
        assertFalse(text.contains("某某科技"));
        assertTrue(text.contains("不说底线"));
        assertTrue(text.contains("两周内"));
        assertTrue(text.contains("不要编"));
    }

    @Test
    void replyOnlyGetsTheCityNotTheDetailedAddress() {
        String text = StructuredPreferenceText.forReply(filled());
        assertTrue(text.contains("上海"));
        assertFalse(text.contains("张江"), "district/street must not reach the chat model");
    }

    @Test
    void replyPutsTheSalaryRuleBeforeTheNumbersSoTruncationKeepsTheRule() {
        String text = StructuredPreferenceText.forReply(filled());
        assertTrue(text.indexOf("谈薪规则") < text.indexOf("12-18K"));
    }

    @Test
    void screeningMarksSoftPreferencesAsNotAReasonToReject() {
        String text = StructuredPreferenceText.forScreening(filled());
        assertTrue(text.contains("薪资底线（硬条件）"));
        assertTrue(text.contains("不能单独作为不投的理由"));
        assertTrue(text.indexOf("硬条件") < text.indexOf("软偏好"));
    }

    @Test
    void emptyPreferencesAddNothing() {
        assertEquals("", StructuredPreferenceText.forScreening(new PreferenceEntity()));
        assertEquals("", StructuredPreferenceText.forScreening(null));
        assertEquals("", StructuredPreferenceText.forReply(new PreferenceEntity()));
        assertEquals("", StructuredPreferenceText.forReply(null));
    }
}
