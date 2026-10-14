package com.maple.ai.job.hunting.utils;

import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.consts.CommunicationStylePrompt;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 用户偏好默认值。
 * <p>
 * 用户偏好只保存个人条件；通用风险识别由系统筛选规则负责。
 */
public final class PreferenceDefaults {

    private static final String LEGACY_DEFAULT_AI_FILTER_PROMPT =
            "请结合完整职位信息和候选人求职方向判断岗位是否适合投递；信息不足但无明显风险时优先允许先沟通确认，不要仅因职位标题或单个关键词过滤。";

    public static final String DEFAULT_RISK_PACK_VERSION = "risk-pack-20260829-v2";
    public static final String PERSONAL_PREFERENCE_DEFAULTS_VERSION = "personal-preferences-20261011-v1";
    public static final String DEFAULT_AI_FILTER_PROMPT =
            "请结合完整职位信息、候选人简历和求职要求筛选岗位；通用风险由系统底层规则识别，信息不足但没有明确风险时先进入聊天确认。";
    public static final String DEFAULT_JOB_GOAL =
            "优先结合我的简历经历、技能和当前岗位职责判断匹配度；不要假设我未说明的行业、城市、薪资或岗位偏好。";
    public static final String DEFAULT_UNACCEPTABLE_CONDITIONS =
            "除系统识别的明确风险外，暂未设置额外的个人硬性限制。";
    public static final String DEFAULT_CLARIFY_CONDITIONS =
            "薪资构成、实际工作内容、办公地点或方式、工时与加班、出差或驻场、试用期等重要信息未写清时，优先提醒我向 HR 确认。";
    public static final String DEFAULT_COMMUNICATION_PREFERENCES =
            "保持礼貌、自然、简洁；只依据我的简历和已提供的信息回答，不编造经历；涉及电话或微信、面试安排、薪资、地点、到岗和合同承诺时，先由我本人确认。";

    private static final List<String> DEFAULT_JOB_CONTENT_EXCLUDE = List.of(
            "电话销售", "电销", "网络销售", "销售专员", "销售代表", "销售顾问", "邀约客户", "客户邀约",
            "地推", "拉新", "陌拜", "招商", "获客", "转化", "办理业务", "无责底薪", "高提成",
            "课程顾问", "招生顾问", "保险代理", "贷款", "信贷",
            "房产中介", "房地产经纪", "房东助手", "房源", "置业顾问", "经纪人", "带看",
            "外卖", "骑手", "配送", "送餐", "跑腿", "众包", "配送站", "站点直招", "站长",
            "娱乐主播", "语音主播", "聊天主播", "陪聊", "礼物流水", "直播平台",
            "日结", "短期兼职", "暑假工", "夜班", "倒班", "频繁出差", "高薪无门槛"
    );

    private PreferenceDefaults() {
    }

    public static PreferenceEntity applyDefaults(PreferenceEntity preference) {
        PreferenceEntity result = applyDefaultPersonalPreferences(applyDefaultRiskPack(preference));
        if (result.getAiSeatRevision() == null || result.getAiSeatRevision() < 0) {
            result.setAiSeatRevision(0L);
        }
        return result;
    }

    /**
     * These fields are editable starting guidance for ordinary users.
     * The version marker makes an intentional clear stable instead of
     * repopulating the text on every load.
     */
    public static PreferenceEntity applyDefaultPersonalPreferences(PreferenceEntity preference) {
        PreferenceEntity result = preference == null ? new PreferenceEntity() : preference;
        if (StringUtils.isNotBlank(result.getPdpV())) {
            return result;
        }
        if (StringUtils.isBlank(result.getJobGoal())) {
            result.setJobGoal(DEFAULT_JOB_GOAL);
        }
        if (StringUtils.isBlank(result.getUnacceptableConditions())) {
            result.setUnacceptableConditions(DEFAULT_UNACCEPTABLE_CONDITIONS);
        }
        if (StringUtils.isBlank(result.getClarifyConditions())) {
            result.setClarifyConditions(DEFAULT_CLARIFY_CONDITIONS);
        }
        if (StringUtils.isBlank(result.getCommunicationPreferences())) {
            result.setCommunicationPreferences(DEFAULT_COMMUNICATION_PREFERENCES);
        }
        result.setPdpV(PERSONAL_PREFERENCE_DEFAULTS_VERSION);
        return result;
    }

    public static PreferenceEntity applyDefaultRiskPack(PreferenceEntity preference) {
        PreferenceEntity result = preference == null ? new PreferenceEntity() : preference;
        if (StringUtils.isBlank(result.getCommunicationStyle())) {
            result.setCommunicationStyle(CommunicationStylePrompt.DEFAULT_STYLE);
        } else {
            result.setCommunicationStyle(CommunicationStylePrompt.normalize(result.getCommunicationStyle()));
        }
        if (isKnownLegacyDefault(result)) {
            result.setAfE(true);
            result.setAf(DEFAULT_AI_FILTER_PROMPT);
            result.setDrpV(DEFAULT_RISK_PACK_VERSION);
            return result;
        }
        if (StringUtils.isNotBlank(result.getDrpV())) {
            if (result.isAfE() && StringUtils.isBlank(result.getAf())) {
                result.setAf(DEFAULT_AI_FILTER_PROMPT);
            }
            if (!result.isJceE() && result.getJce() == null) {
                result.setJce(new ArrayList<>());
            }
            return result;
        }
        if (!hasExplicitRiskCustomization(result)) {
            result.setAfE(true);
            result.setAf(DEFAULT_AI_FILTER_PROMPT);
            result.setJceE(false);
            result.setJce(new ArrayList<>());
        }
        result.setDrpV(DEFAULT_RISK_PACK_VERSION);
        return result;
    }

    /**
     * Personal job requirements do not count as an override of the common
     * system risk rules. Only fields that directly change risk filtering do.
     */
    private static boolean hasExplicitRiskCustomization(PreferenceEntity preference) {
        return preference.isAfE()
                || StringUtils.isNotBlank(preference.getAf())
                || preference.isJceE()
                || !isEmpty(preference.getJce());
    }

    private static boolean isKnownLegacyDefault(PreferenceEntity preference) {
        return "risk-pack-20260818-v1".equals(preference.getDrpV())
                && preference.isAfE()
                && LEGACY_DEFAULT_AI_FILTER_PROMPT.equals(StringUtils.trimToEmpty(preference.getAf()));
    }

    public static List<String> defaultJobContentExclude() {
        return DEFAULT_JOB_CONTENT_EXCLUDE;
    }

    private static List<String> merge(List<String> existing, List<String> defaults) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existing != null) {
            existing.stream()
                    .filter(StringUtils::isNotBlank)
                    .map(String::trim)
                    .forEach(merged::add);
        }
        defaults.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .forEach(merged::add);
        return new ArrayList<>(merged);
    }

    private static boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }
}
