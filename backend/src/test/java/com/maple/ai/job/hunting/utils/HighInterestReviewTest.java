package com.maple.ai.job.hunting.utils;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighInterestReviewTest {

    @Test
    void qualifiedHighInterestNeedsCompleteContextPromisingJobLowRiskAndHrProgress() {
        JSONObject jobInfo = completeJob("AIGC内容运营助理", "负责AI素材整理、表格记录、内容审核");
        String question = "这边觉得比较合适，方便明天下午线上面试吗？";

        HighInterestReview.Result result = HighInterestReview.evaluate(
                jobInfo,
                question,
                "",
                JobContextCompleteness.evaluate(jobInfo, question));

        assertTrue(result.qualified());
        assertTrue(result.contextComplete());
        assertTrue(result.promisingJob());
        assertTrue(result.companyLowRisk());
        assertTrue(result.hrProgressSignal());
    }

    @Test
    void chatRoundsOrGenericMessagesDoNotBecomeHighInterestWithoutHrProgress() {
        JSONObject jobInfo = completeJob("AIGC内容运营助理", "负责AI素材整理、表格记录、内容审核");
        String question = "好的，我看一下";

        HighInterestReview.Result result = HighInterestReview.evaluate(
                jobInfo,
                question,
                "",
                JobContextCompleteness.evaluate(jobInfo, question));

        assertFalse(result.qualified());
        assertFalse(result.hrProgressSignal());
    }

    @Test
    void salaryInterestQuestionDoesNotTriggerHandoffWithoutConcreteInterviewArrangement() {
        JSONObject jobInfo = completeJob("AIGC内容运营助理", "负责AI素材整理、表格记录、内容审核");
        String question = "咱有份工作符合你的期望薪资，要了解下么？";

        HighInterestReview.Result result = HighInterestReview.evaluate(
                jobInfo,
                question,
                "",
                JobContextCompleteness.evaluate(jobInfo, question));

        assertFalse(result.qualified());
        assertFalse(result.hrProgressSignal());
    }

    @Test
    void riskPackSignalsBlockHighInterestEvenIfHrMentionsInterview() {
        JSONObject jobInfo = completeJob("电话销售", "负责外呼获客、转化客户、无责底薪加高提成");
        String question = "方便今天下午来面试吗？";

        HighInterestReview.Result result = HighInterestReview.evaluate(
                jobInfo,
                question,
                "",
                JobContextCompleteness.evaluate(jobInfo, question));

        assertFalse(result.qualified());
        assertFalse(result.companyLowRisk());
        assertTrue(result.softRiskSignal().contains("销售"));
    }

    @Test
    void weakJobContextBlocksHighInterest() {
        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", "招聘负责人");
        String question = "方便电话沟通吗？";

        HighInterestReview.Result result = HighInterestReview.evaluate(
                jobInfo,
                question,
                "",
                JobContextCompleteness.evaluate(jobInfo, question));

        assertFalse(result.qualified());
        assertFalse(result.contextComplete());
    }

    @Test
    void ordinaryJobDirectionCanQualifyWithoutHardcodedAiOrAigcTerms() {
        JSONObject jobInfo = completeJob("行政助理", "负责资料整理、表格记录和日常协调");
        String question = "方便明天下午线上面试吗？";

        HighInterestReview.Result result = HighInterestReview.evaluate(
                jobInfo,
                question,
                "可以进一步了解岗位安排",
                JobContextCompleteness.evaluate(jobInfo, question));

        assertTrue(result.qualified());
        assertTrue(result.promisingJob());
    }

    private JSONObject completeJob(String position, String description) {
        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", position + "-招聘负责人");
        jobInfo.put("visibleJobCard",
                "公司:示例科技 | 岗位:" + position
                        + " | 薪资:5-8K | 地点:上海浦东 | 经验:无需经验 | 学历:大专 | 规模:20-99人 | 职位描述:"
                        + description);
        return jobInfo;
    }
}
