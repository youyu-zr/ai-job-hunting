package com.maple.ai.job.hunting.utils;

import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 高意向复核闸门。
 *
 * <p>它只负责“是否值得升级为高意向/人工接管提醒”的通用判断，不写入任何单个候选人的
 * 城市、学历、姓名、手机号或岗位偏好。个人差异应来自简历、偏好和模型上下文。</p>
 */
public final class HighInterestReview {

    private HighInterestReview() {
    }

    public static Result evaluate(JSONObject jobInfo,
                                  String hrMessage,
                                  String aiAnswer,
                                  JobContextCompleteness.Result completeness) {
        String context = buildContext(jobInfo);
        String allText = (context + "\n" + StringUtils.defaultString(hrMessage) + "\n"
                + StringUtils.defaultString(aiAnswer)).replaceAll("\\s+", " ").trim();

        boolean contextComplete = completeness != null && !completeness.lowConfidenceForHighInterest();
        boolean promisingJob = hasPromisingJobDirection(jobInfo);
        Optional<String> hardRisk = JobRiskKeywordUtil.findHardRejectSignal(allText);
        Optional<String> softRisk = JobRiskKeywordUtil.findExcludeKeyword(allText, PreferenceDefaults.defaultJobContentExclude());
        boolean companyLowRisk = hardRisk.isEmpty() && softRisk.isEmpty();
        boolean hrProgressSignal = hasConcreteHrProgressSignal(hrMessage);

        List<String> blockedReasons = new ArrayList<>();
        if (!contextComplete) {
            blockedReasons.add("职位上下文不完整");
        }
        if (!promisingJob) {
            blockedReasons.add("岗位方向未命中高质量方向");
        }
        hardRisk.ifPresent(signal -> blockedReasons.add("硬风险:" + signal));
        softRisk.ifPresent(signal -> blockedReasons.add("风险包:" + signal));
        if (!hrProgressSignal) {
            blockedReasons.add("缺少HR推进信号");
        }

        boolean qualified = blockedReasons.isEmpty();
        return new Result(
                qualified,
                contextComplete,
                promisingJob,
                companyLowRisk,
                hrProgressSignal,
                hardRisk.orElse(""),
                softRisk.orElse(""),
                qualified ? "通过：上下文完整、岗位方向匹配、风险低、HR有推进信号"
                        : "未通过：" + String.join("；", blockedReasons)
        );
    }

    public static boolean hasPromisingJobDirection(JSONObject jobInfo) {
        return StringUtils.isNotBlank(buildContext(jobInfo));
    }

    public static boolean isLowRiskForHighInterest(JSONObject jobInfo, String hrMessage) {
        String text = buildContext(jobInfo) + "\n" + StringUtils.defaultString(hrMessage);
        return JobRiskKeywordUtil.findHardRejectSignal(text).isEmpty()
                && JobRiskKeywordUtil.findExcludeKeyword(text, PreferenceDefaults.defaultJobContentExclude()).isEmpty();
    }

    public static boolean hasHrProgressSignal(String hrMessage) {
        if (StringUtils.isBlank(hrMessage)) {
            return false;
        }
        String text = hrMessage.replaceAll("\\s+", "");
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return text.matches(".*(面试|面谈|面聊|复试|初试|终试|笔试|试岗|视频面|电话面|线上面|线下面|约面|约时间|哪天方便|几点方便|方便电话|电话沟通|加微信|微信沟通|联系方式|手机号|手机号码|薪资|工资|到岗|入职|试用|转正|合适的话|可以安排|安排一下).*");
    }

    /**
     * Initial interest checks such as salary questions or "要了解吗" are not
     * enough to trigger a handoff. A concrete schedule, interview card, or
     * explicit interview arrangement is required.
     */
    public static boolean hasConcreteHrProgressSignal(String hrMessage) {
        if (StringUtils.isBlank(hrMessage)) {
            return false;
        }
        String text = hrMessage.replaceAll("\\s+", "");
        boolean hasInterview = text.matches(".*(面试|面谈|初试|复试|试岗|视频面|线上面|线下面|约面).*");
        boolean hasConcreteTime = text.matches(".*(今天|明天|后天|周一|周二|周三|周四|周五|周六|周日|\\d{1,2}月\\d{1,2}日|\\d{1,2}点|上午|下午|晚上).*");
        boolean hasArrangement = text.matches(".*(面试邀请|面试卡片|请点击接受|已经为您安排|面试地址|到公司参加).*");
        boolean hasContactArrangement = ConversationGateUtil.isContactExchangeQuestion(hrMessage);
        return (hasInterview && (hasConcreteTime || hasArrangement)) || hasContactArrangement;
    }

    private static String buildContext(JSONObject jobInfo) {
        if (jobInfo == null || jobInfo.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        append(builder, jobInfo.getString("jobTitle"));
        append(builder, jobInfo.getString("visibleJobCard"));
        append(builder, jobInfo.getString("jobContext"));
        for (String key : List.of(
                "jobName", "positionName", "position", "title",
                "companyName", "brandName", "company",
                "salary", "salaryDesc", "jobSalary",
                "cityName", "areaDistrict", "businessDistrict", "address", "addressDesc", "poiName", "location",
                "postDescription", "jobDescription", "description", "content",
                "jobExperience", "experience", "jobDegree", "degree", "education",
                "brandScaleName", "companyScale", "scale", "labels")) {
            append(builder, jobInfo.getString(key));
        }
        return builder.toString().replaceAll("\\s+", " ").trim();
    }

    private static void append(StringBuilder builder, String value) {
        if (StringUtils.isNotBlank(value)) {
            builder.append(value).append('\n');
        }
    }

    public record Result(boolean qualified,
                         boolean contextComplete,
                         boolean promisingJob,
                         boolean companyLowRisk,
                         boolean hrProgressSignal,
                         String hardRiskSignal,
                         String softRiskSignal,
                         String summary) {
    }
}
