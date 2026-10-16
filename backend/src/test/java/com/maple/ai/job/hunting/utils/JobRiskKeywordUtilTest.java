package com.maple.ai.job.hunting.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobRiskKeywordUtilTest {

    @Test
    void shouldHardRejectExplicitLowValuePromotionJobs() {
        assertTrue(JobRiskKeywordUtil.findHardRejectSignal("招聘电话销售，底薪加提成").isPresent());
        assertTrue(JobRiskKeywordUtil.findHardRejectSignal("线下地推拉新，日结结算").isPresent());
        assertTrue(JobRiskKeywordUtil.findHardRejectSignal("日结短期兼职，扫码进群上岗").isPresent());
    }

    @Test
    void shouldStillHardRejectFeeAndAccountTradingRisks() {
        assertTrue(JobRiskKeywordUtil.findHardRejectSignal("先交费培训，保证金2000").isPresent());
        assertTrue(JobRiskKeywordUtil.findHardRejectSignal("账号租借/买卖账号").isPresent());
        assertTrue(JobRiskKeywordUtil.findHardRejectSignal("刷单刷流水兼职").isPresent());
        assertTrue(JobRiskKeywordUtil.findHardRejectSignal("传销拉人头，团队裂变拿返利").isPresent());
        assertTrue(JobRiskKeywordUtil.findHardRejectSignal("地推拉新，日结结算").isPresent());
        assertTrue(JobRiskKeywordUtil.findHardRejectSignal("招聘广告引流，扫码进群领取岗位资料").isPresent());
    }

    @Test
    void shouldApplyNegationAcrossCoordinatedRiskTermsInTheSameClause() {
        List<String> keywords = List.of("销售", "收费", "传销");

        assertTrue(JobRiskKeywordUtil.findExcludeKeyword("岗位不涉及销售和收费", keywords).isEmpty());
        assertTrue(JobRiskKeywordUtil.findExcludeKeyword("工作不含销售、收费内容", keywords).isEmpty());
        assertTrue(JobRiskKeywordUtil.findHardRejectSignal("岗位明确不涉及传销和拉人头").isEmpty());
    }

    @Test
    void shouldStopNegationAtClauseBoundaryOrAdversativeTurn() {
        List<String> keywords = List.of("销售", "收费");

        assertEquals("收费", JobRiskKeywordUtil.findExcludeKeyword("岗位不涉及销售，但需要收费", keywords).orElseThrow());
        assertEquals("收费", JobRiskKeywordUtil.findExcludeKeyword("岗位不是销售。入职前需要收费", keywords).orElseThrow());
        assertEquals("押金", JobRiskKeywordUtil.findExcludeKeyword("岗位不涉及培训贷另有押金", List.of("培训贷", "押金")).orElseThrow());
    }

    @Test
    void shouldRejectAiHotWordWrappedFinancialSales() {
        String text = "AI豆包 人工智能 新风口！互联网金融。无责7000+40%提成，综合1.5W-3W+。只要求简单电脑操作。";

        assertTrue(JobRiskKeywordUtil.findCompoundedHardRiskSignal(text).isPresent());
    }

    @Test
    void shouldRejectCustomerApplicationSalesWithHighCommission() {
        String text = "B端 AI销售，信托行业。审核客户申请资料，安排客户办理手续，底薪10200元+40%提成，综合月入3万。";

        assertTrue(JobRiskKeywordUtil.findCompoundedHardRiskSignal(text).isPresent());
    }

    @Test
    void shouldKeepNormalAiQualityRoleAvailable() {
        String text = "AI质检实习生，互联网行业，5-8K。通过AI质检系统和人工方式对客服聊天记录评分，熟练使用Excel，输出质检报告。";

        assertTrue(JobRiskKeywordUtil.findCompoundedHardRiskSignal(text).isEmpty());
    }
}
