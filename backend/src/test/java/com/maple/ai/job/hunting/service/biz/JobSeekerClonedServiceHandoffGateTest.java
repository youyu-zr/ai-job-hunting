package com.maple.ai.job.hunting.service.biz;

import com.alibaba.fastjson.JSONObject;
import com.maple.ai.job.hunting.model.param.JobSeekerClonedParam;
import com.maple.ai.job.hunting.utils.JobContextCompleteness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobSeekerClonedServiceHandoffGateTest {

    @Test
    void titleOnlyContactRequestDoesNotQualifyForEmail() {
        JobSeekerClonedParam param = param(
                "咱简历跟我们岗位匹配度很高哈，方便发份简历或者互换电话吗，更快与咱取得联系~",
                titleOnlyJob());

        assertFalse(review(param, "<联系方式需要人工确认>").qualified());
    }

    @Test
    void completeLowRiskContactRequestQualifiesForEmail() {
        JobSeekerClonedParam param = param("岗位匹配度不错，方便交换联系方式吗？", completeJob());

        assertTrue(review(param, "<联系方式需要人工确认>").qualified());
    }

    @Test
    void ordinaryPhoneConversationDoesNotQualifyForEmail() {
        JobSeekerClonedParam param = param("岗位匹配度不错，方便电话沟通一下吗？", completeJob());

        assertFalse(review(param, "您好，可以先了解一下岗位职责和团队情况").qualified());
    }

    @Test
    void riskyContactRequestDoesNotQualifyForEmail() {
        JSONObject jobInfo = completeJob();
        jobInfo.put("visibleJobCard",
                "公司:示例科技 | 岗位:电话销售 | 薪资:10-20K | 地点:商丘 | 经验:无需经验 | 学历:不限 | "
                        + "规模:20-99人 | 职位描述:负责外呼获客，无责底薪加高提成");
        JobSeekerClonedParam param = param("方便电话沟通一下吗？", jobInfo);

        assertFalse(review(param, "<联系方式需要人工确认>").qualified());
    }

    @Test
    void normalGreetingDoesNotQualifyForEmail() {
        JobSeekerClonedParam param = param("你好", completeJob());

        assertFalse(review(param, "您好，请问具体工作内容是什么？").qualified());
    }

    @Test
    void highInterestEmailAloneDoesNotEnableAiSeatStop() {
        assertFalse(JobSeekerClonedService.shouldStopAiSeat(false, true));
        assertTrue(JobSeekerClonedService.shouldStopAiSeat(true, true));
    }

    private com.maple.ai.job.hunting.utils.HighInterestReview.Result review(
            JobSeekerClonedParam param, String answer) {
        JobContextCompleteness.Result completeness = JobContextCompleteness.evaluate(
                param.getJobInfo(), param.getQuestion());
        return JobSeekerClonedService.reviewHandoffQualification(param, answer, completeness);
    }

    private JobSeekerClonedParam param(String question, JSONObject jobInfo) {
        return new JobSeekerClonedParam(question, "test-job", jobInfo);
    }

    private JSONObject titleOnlyJob() {
        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", "商丘量子引擎科技-人事专员-熊庆丰");
        jobInfo.put("visibleJobCard", "当前联系人岗位:商丘量子引擎科技-人事专员-熊庆丰");
        return jobInfo;
    }

    private JSONObject completeJob() {
        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", "示例科技-AIGC内容运营助理-招聘负责人");
        jobInfo.put("visibleJobCard",
                "公司:示例科技 | 岗位:AIGC内容运营助理 | 薪资:5-8K | 地点:商丘 | 经验:无需经验 | 学历:中专 | "
                        + "规模:20-99人 | 职位描述:负责AI素材整理、Excel记录和内容审核");
        return jobInfo;
    }
}
