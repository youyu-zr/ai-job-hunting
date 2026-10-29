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
    void replyAsksForTheUpperHalfOfTheRangeButNeverAgreesToANumber() {
        String text = StructuredPreferenceText.forReply(filled());
        assertTrue(text.contains("报 15-18K"), text);
        assertTrue(text.contains("岗位标的薪资上限比这个低时"), text);
        assertTrue(text.contains("不答应也不拒绝"));
        assertTrue(text.contains("希望能再往上谈"));
        assertFalse(text.contains("10K"));
    }

    @Test
    void upperHalfHandlesOneSidedRanges() {
        PreferenceEntity onlyMin = new PreferenceEntity();
        onlyMin.setSalaryMinK(12);
        assertEquals("12K 以上", StructuredPreferenceText.upperHalf(onlyMin));
        PreferenceEntity onlyMax = new PreferenceEntity();
        onlyMax.setSalaryMaxK(20);
        assertEquals("20K 左右", StructuredPreferenceText.upperHalf(onlyMax));
        PreferenceEntity odd = new PreferenceEntity();
        odd.setSalaryMinK(11);
        odd.setSalaryMaxK(16);
        assertEquals("14-16K", StructuredPreferenceText.upperHalf(odd));
        PreferenceEntity narrow = new PreferenceEntity();
        narrow.setSalaryMinK(8);
        narrow.setSalaryMaxK(9);
        assertEquals("9K", StructuredPreferenceText.upperHalf(narrow));
        PreferenceEntity reversed = new PreferenceEntity();
        reversed.setSalaryMinK(18);
        reversed.setSalaryMaxK(12);
        assertEquals("15-18K", StructuredPreferenceText.upperHalf(reversed));
        PreferenceEntity highFloor = new PreferenceEntity();
        highFloor.setSalaryMinK(8);
        highFloor.setSalaryMaxK(20);
        highFloor.setSalaryFloorK(18);
        assertEquals("18-20K", StructuredPreferenceText.upperHalf(highFloor));
        assertTrue(StructuredPreferenceText.forReply(reversed).contains("12-18K"),
                StructuredPreferenceText.forReply(reversed));
    }

    @Test
    void onlyMaxSalaryIsNeverWrittenAsAnInvitationToGoLower() {
        PreferenceEntity onlyMax = new PreferenceEntity();
        onlyMax.setSalaryMaxK(18);
        String text = StructuredPreferenceText.forReply(onlyMax);
        assertFalse(text.contains("以下"), text);
        assertTrue(text.contains("18K 左右"), text);
    }

    @Test
    void chatModelGetsContactPlaceholdersOnlyWhenTheUserAllowsIt() {
        PreferenceEntity p = filled();
        p.setWechatId("zr_2026abc");
        assertFalse(StructuredPreferenceText.forReply(p).contains("【我的微信】"), "default is PAUSE");
        p.setStopContact("DECLINE");
        assertFalse(StructuredPreferenceText.forReply(p).contains("【我的微信】"));
        p.setStopContact("ALLOW");
        // 旧版「让 AI 处理、但不写号码」的用户：还没确认，不给。
        assertFalse(StructuredPreferenceText.forReply(p).contains("【我的微信】"));
        p.setContactShareE(true);
        String reply = StructuredPreferenceText.forReply(p);
        assertTrue(reply.contains("【我的微信】"), reply);
        assertTrue(reply.contains("【我的手机】"), reply);
        // 真实号码从不交给模型
        assertFalse(reply.contains("zr_2026abc"));
        assertFalse(StructuredPreferenceText.forReply(p, false).contains("【我的微信】"), "triage/review never get it");
        assertFalse(StructuredPreferenceText.forScreening(p).contains("zr_2026abc"));
    }

    @Test
    void emptyPreferencesAddNothing() {
        assertEquals("", StructuredPreferenceText.forScreening(new PreferenceEntity()));
        assertEquals("", StructuredPreferenceText.forScreening(null));
        assertEquals("", StructuredPreferenceText.forReply(new PreferenceEntity()));
        assertEquals("", StructuredPreferenceText.forReply(null));
    }
}
