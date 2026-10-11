package com.maple.ai.job.hunting.utils;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobContextCompletenessTest {

    @Test
    void completeVisibleJobCardGetsHighScore() {
        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", "AIGC内容运营助理-上海-招聘负责人");
        jobInfo.put("visibleJobCard", "公司:示例科技 | 岗位:AIGC内容运营助理 | 薪资:5-8K | 地点:上海浦东 | 经验:无需经验 | 学历:大专 | 规模:20-99人 | 职位描述:负责AI素材整理、表格记录、内容审核");

        JobContextCompleteness.Result result = JobContextCompleteness.evaluate(jobInfo, "可以了解一下岗位内容吗");

        assertTrue(result.score() >= 85);
        assertFalse(result.lowConfidenceForHighInterest());
    }

    @Test
    void missingJobContextStaysLowConfidence() {
        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", "HR-王经理");

        JobContextCompleteness.Result result = JobContextCompleteness.evaluate(jobInfo, "方便电话吗");

        assertTrue(result.score() < JobContextCompleteness.HIGH_INTEREST_MIN_SCORE);
        assertTrue(result.lowConfidenceForHighInterest());
        assertTrue(result.missingFields().contains("description"));
        assertTrue(result.promptHint().contains("missingFields"));
    }

    @Test
    void contactTitleDoesNotPretendCompanyScaleWasRead() {
        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", "商丘量子引擎科技-人事专员-熊庆丰");
        jobInfo.put("visibleJobCard", "当前联系人岗位:商丘量子引擎科技-人事专员-熊庆丰");

        JobContextCompleteness.Result result = JobContextCompleteness.evaluate(
                jobInfo, "方便发份简历或者互换电话吗？");

        assertFalse(result.presentFields().contains("companyScale"));
        assertTrue(result.missingFields().contains("companyScale"));
        assertTrue(result.lowConfidenceForHighInterest());
    }

    @Test
    void completeJobContextFieldIsUsedWhenChatReceivesExtendedContext() {
        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", "全栈开发实习生");
        jobInfo.put("jobContext", "公司:示例科技 | 薪资:6-9K | 地点:上海浦东 | 职位描述:负责Vue和Node.js开发 | 学历:本科");

        JobContextCompleteness.Result result = JobContextCompleteness.evaluate(jobInfo, "可以聊聊岗位吗？");

        assertTrue(result.presentFields().contains("company"));
        assertTrue(result.presentFields().contains("salary"));
        assertTrue(result.presentFields().contains("description"));
        assertTrue(result.presentFields().contains("education"));
    }
}
