package com.maple.ai.job.hunting.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Centralized hard-risk keyword matching for the job seeker flow.
 * Keep this deterministic and conservative: it is used before AI scoring to avoid
 * clearly unsuitable / high-risk jobs entering delivery or interview handoff flows.
 */
public final class JobRiskKeywordUtil {

    private static final List<String> DIRECT_NEGATION_PREFIXES = List.of(
            "不接受", "不支持", "不需要", "不要求", "不涉及", "不包括", "不含",
            "不用", "无需", "没有", "禁止", "避免", "不做", "不是", "非", "无", "不");

    private static final List<String> SCOPED_NEGATION_PREFIXES = List.of(
            "不涉及", "不包括", "不含", "不做", "禁止", "避免", "没有", "不是", "非");

    private static final List<String> NEGATION_BREAKERS = List.of(
            "但是", "但", "不过", "然而", "却", "仍然", "仍", "需要", "需", "会", "包含", "存在");

    private static final List<String> HARD_REJECT_KEYWORDS = List.of(
            "培训贷", "押金", "保证金", "收费培训", "先交费", "先缴费", "培训费", "设备费", "无薪试岗", "体检费", "住宿费", "招转培",
            "刷单", "刷流水", "账号租借", "租账号", "账号交易", "买卖账号", "代实名", "博彩", "擦边",
            "传销", "拉人头", "层级返利", "入会费", "团队裂变", "虚假招聘", "招聘广告", "招聘引流", "广告引流", "扫码进群", "加群领取",
            "电话销售", "电销", "地推", "拉新", "日结", "短期兼职",
            "陪聊", "语音主播", "聊天主播", "娱乐主播", "出镜主播", "直播礼物", "礼物流水", "颜值主播", "语音厅", "直播平台",
            "专业网球", "网球教练", "足球教练", "篮球教练");

    private static final Pattern HIGH_COMMISSION_PATTERN = Pattern.compile(
            "(?:无责|底薪|保底)[^。；;\\n]{0,24}(?:提成|佣金)|\\d{1,2}\\s*%\\s*提成|综合[^。；;\\n]{0,12}(?:1?\\d(?:\\.\\d+)?万|[2-9]\\d万)");

    private static final List<String> FINANCIAL_CONTEXT = List.of(
            "互联网金融", "金融咨询", "信托", "贷款", "信用卡", "融资", "银行业务");

    private static final List<String> CUSTOMER_BUSINESS_ACTION = List.of(
            "客户申请", "办理手续", "办理业务", "客户资质", "跟进客户", "开发客户", "获客", "转化", "开单", "客户提交");

    private static final List<String> AI_MARKETING_TITLE = List.of(
            "AI豆包", "人工智能新风口", "AI销售", "AI顾问", "AI推广");

    private static final List<String> LOW_BARRIER_SIGNALS = List.of(
            "简单电脑操作", "工作轻松简单", "态度端正", "普通话标准", "无经验也可以");

    private JobRiskKeywordUtil() {
    }

    public static Optional<String> findHardRejectSignal(String filterText) {
        return findExcludeKeyword(filterText, HARD_REJECT_KEYWORDS);
    }

    /**
     * Detect combinations that are misleading when a trendy AI title masks
     * financial/customer-acquisition work with unusually high commission.
     * A single occurrence is intentionally not enough to avoid rejecting
     * ordinary AI, banking, or entry-level office roles.
     */
    public static Optional<String> findCompoundedHardRiskSignal(String filterText) {
        if (StringUtils.isBlank(filterText)) {
            return Optional.empty();
        }
        String text = filterText.replaceAll("\\s+", "");
        boolean financial = containsAny(text, FINANCIAL_CONTEXT);
        boolean customerAction = containsAny(text, CUSTOMER_BUSINESS_ACTION);
        boolean highCommission = HIGH_COMMISSION_PATTERN.matcher(text).find();
        boolean aiMarketingTitle = containsAny(text, AI_MARKETING_TITLE);
        boolean lowBarrier = containsAny(text, LOW_BARRIER_SIGNALS);

        if (financial && customerAction && highCommission) {
            return Optional.of("金融/客户业务与高提成组合");
        }
        if (financial && highCommission && (aiMarketingTitle || lowBarrier)) {
            return Optional.of("AI标题或低门槛与金融高提成组合");
        }
        if (aiMarketingTitle && highCommission && customerAction) {
            return Optional.of("AI标题与客户业务高提成组合");
        }
        return Optional.empty();
    }

    private static boolean containsAny(String text, List<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }

    public static Optional<String> findExcludeKeyword(String filterText, List<String> excludeKeywords) {
        if (StringUtils.isBlank(filterText) || excludeKeywords == null || excludeKeywords.isEmpty()) {
            return Optional.empty();
        }
        for (String rawKeyword : excludeKeywords) {
            if (StringUtils.isBlank(rawKeyword)) {
                continue;
            }
            String keyword = rawKeyword.trim();
            if (StringUtils.isBlank(keyword)) {
                continue;
            }
            int from = 0;
            while (from < filterText.length()) {
                int index = filterText.indexOf(keyword, from);
                if (index < 0) {
                    break;
                }
                if (!isNegatedKeywordOccurrence(filterText, index)) {
                    return Optional.of(keyword);
                }
                from = index + keyword.length();
            }
        }
        return Optional.empty();
    }

    private static boolean isNegatedKeywordOccurrence(String text, int keywordIndex) {
        String clausePrefix = currentClausePrefix(text, keywordIndex)
                .replaceAll("[\\s\\u3000:：/|、]+", "");

        if (DIRECT_NEGATION_PREFIXES.stream().anyMatch(clausePrefix::endsWith)) {
            return true;
        }

        for (String negation : SCOPED_NEGATION_PREFIXES) {
            int negationIndex = clausePrefix.lastIndexOf(negation);
            if (negationIndex < 0) {
                continue;
            }
            String scope = clausePrefix.substring(negationIndex + negation.length());
            if (scope.length() <= 16 && NEGATION_BREAKERS.stream().noneMatch(scope::contains)) {
                return true;
            }
        }
        return false;
    }

    private static String currentClausePrefix(String text, int keywordIndex) {
        int start = Math.max(0, keywordIndex - 28);
        String prefix = text.substring(start, keywordIndex);
        int clauseStart = -1;
        for (char delimiter : new char[]{'，', ',', '。', '.', '；', ';', '！', '!', '？', '?', '\n', '\r'}) {
            clauseStart = Math.max(clauseStart, prefix.lastIndexOf(delimiter));
        }
        return prefix.substring(clauseStart + 1);
    }
}
