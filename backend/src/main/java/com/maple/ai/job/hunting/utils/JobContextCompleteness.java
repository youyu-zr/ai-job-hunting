package com.maple.ai.job.hunting.utils;

import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Scores how complete the current job context is before an AI-seat reply is generated.
 * <p>
 * This is intentionally a thin, deterministic layer: it does not decide whether to
 * deliver or reject a job. It only answers "what did we read, and what is missing?"
 * so later filters / handoff logic can stay conservative when context is weak.
 */
public final class JobContextCompleteness {

    public static final int HIGH_INTEREST_MIN_SCORE = 55;

    private static final List<FieldRule> FIELD_RULES = List.of(
            new FieldRule("jobTitle", List.of("jobTitle", "jobName", "positionName", "position", "title"),
                    List.of("岗位", "职位", "job", "position")),
            new FieldRule("company", List.of("companyName", "brandName", "company"),
                    List.of("公司", "品牌", "company", "brand")),
            new FieldRule("salary", List.of("salary", "salaryDesc", "jobSalary"),
                    List.of("薪资", "工资", "月薪", "日薪", "K", "k", "/天", "元/天")),
            new FieldRule("location", List.of("cityName", "areaDistrict", "businessDistrict", "address", "addressDesc", "poiName", "location"),
                    List.of("地点", "地址", "通勤", "上海", "北京", "广州", "深圳", "杭州", "location", "address")),
            new FieldRule("description", List.of("postDescription", "jobDescription", "description", "content"),
                    List.of("职位描述", "岗位职责", "工作内容", "任职要求", "岗位要求", "description", "responsibility")),
            new FieldRule("experience", List.of("jobExperience", "experience"),
                    List.of("经验", "无需经验", "应届", "在校", "实习", "experience")),
            new FieldRule("education", List.of("jobDegree", "degree", "education"),
                    List.of("学历", "大专", "中专", "本科", "不限", "education")),
            new FieldRule("companyScale", List.of("brandScaleName", "companyScale", "scale"),
                    List.of("规模", "融资", "行业", "companyScale")),
            new FieldRule("hrMessage", List.of(),
                    List.of())
    );

    private JobContextCompleteness() {
    }

    public static Result evaluate(JSONObject jobInfo, String hrMessage) {
        String context = buildContext(jobInfo, hrMessage);
        List<String> presentFields = new ArrayList<>();
        List<String> missingFields = new ArrayList<>();
        for (FieldRule rule : FIELD_RULES) {
            boolean present;
            if ("hrMessage".equals(rule.name())) {
                present = StringUtils.isNotBlank(hrMessage);
            } else {
                present = hasAnyJsonField(jobInfo, rule.jsonFields()) || containsAny(context, rule.textSignals());
            }
            if (present) {
                presentFields.add(rule.name());
            } else {
                missingFields.add(rule.name());
            }
        }
        int score = Math.round(presentFields.size() * 100f / FIELD_RULES.size());
        return new Result(score, presentFields, missingFields);
    }

    public static String buildContext(JSONObject jobInfo, String hrMessage) {
        StringBuilder builder = new StringBuilder();
        if (jobInfo != null) {
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
                    "brandScaleName", "companyScale", "scale")) {
                append(builder, jobInfo.getString(key));
            }
        }
        append(builder, hrMessage);
        return builder.toString().replaceAll("\\s+", " ").trim();
    }

    public record Result(int score, List<String> presentFields, List<String> missingFields) {
        public boolean lowConfidenceForHighInterest() {
            return score < HIGH_INTEREST_MIN_SCORE;
        }

        public String missingFieldsText() {
            if (missingFields == null || missingFields.isEmpty()) {
                return "none";
            }
            return String.join(",", missingFields);
        }

        public String promptHint() {
            return "Context completeness score=" + score
                    + "/100; missingFields=" + missingFieldsText()
                    + ". If key job/company/location/description fields are missing, ask one short clarifying question before making commitments or treating the role as high-interest.";
        }
    }

    private static boolean hasAnyJsonField(JSONObject jsonObject, List<String> fields) {
        if (jsonObject == null || fields == null || fields.isEmpty()) {
            return false;
        }
        for (String field : fields) {
            String value = Optional.ofNullable(jsonObject.getString(field)).orElse("");
            if (StringUtils.isNotBlank(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String context, List<String> signals) {
        if (StringUtils.isBlank(context) || signals == null || signals.isEmpty()) {
            return false;
        }
        String lower = context.toLowerCase(Locale.ROOT);
        for (String signal : signals) {
            if (StringUtils.isNotBlank(signal) && lower.contains(signal.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void append(StringBuilder builder, String value) {
        if (StringUtils.isNotBlank(value)) {
            builder.append(value).append('\n');
        }
    }

    private record FieldRule(String name, List<String> jsonFields, List<String> textSignals) {
    }
}
