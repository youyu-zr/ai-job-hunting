package com.maple.ai.job.hunting.utils;

import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 发送前的最后一道检查：AI 写好的回复里有没有替用户做了不该做的承诺。
 *
 * <p>只拦用户没选「让 AI 处理」的那几类：答应具体面试/到岗时间、答应某个薪资数字、接受 Offer、
 * 接受了用户「其他底线」里写明不接受的条件。命中就不发，改为停下叫用户。</p>
 *
 * <p>按分句判断：问句（带「吗」「？」）不算答应；「不能接受」「需要确认」这类先去掉再看。</p>
 */
public final class OutboundCommitmentGuard {

    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[，,。.！!？?；;\\n]");
    private static final Pattern CONCRETE_TIME = Pattern.compile(
            "([0-9一二三四五六七八九十两]{1,3}[:：点]|\\d{1,2}月\\d{1,2}[日号]|周[一二三四五六日天]|星期[一二三四五六日天]"
                    + "|今天|明天|后天|下周|上午|下午|晚上|明早)");
    private static final Pattern TIME_CONTEXT = Pattern.compile("(面试|到岗|入职|过去|到公司|能来|过来|到场|报到|上班)");
    private static final Pattern TIME_AGREE = Pattern.compile(
            "(可以|没问题|好的|行的|准时|能到|会到|就这个时间|按时|有空|方便|我到|我来|过去|过来|报到)");
    private static final Pattern SALARY_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?\\s*[kK千万wW]|\\d{4,6}\\s*[元块])");
    private static final Pattern AGREE = Pattern.compile("(接受|没问题|同意|可以的|行的|可以|愿意|好的)");
    /** 「不能接受」「需要确认」等：同意词前面有否定或保留，就不算答应。 */
    private static final Pattern NOT_AGREE = Pattern.compile(
            "(不能|不可以|不太能|无法|没法|难以|不会|不太|不)(接受|同意|可以|行|确认|能到|会到|方便|愿意)"
                    + "|需要确认|要确认|确认一下|确认下|和.{0,6}确认|考虑|稍后|再答复|再回复|再给您|回头");

    private OutboundCommitmentGuard() {
    }

    public static Optional<StopEventPolicy.Event> find(String answer, PreferenceEntity preference) {
        if (StringUtils.isBlank(answer)) {
            return Optional.empty();
        }
        String whole = answer.replaceAll("\\s+", "");
        boolean holdsBack = NOT_AGREE.matcher(whole).find();
        for (String clause : CLAUSE_SPLIT.split(whole)) {
            if (clause.isBlank() || isQuestion(clause)) {
                continue;
            }
            String text = NOT_AGREE.matcher(clause).replaceAll("");
            if (!holdsBack && promisesTime(text) && notAllowed(StopEventPolicy.Event.INTERVIEW_TIME, preference)) {
                return Optional.of(StopEventPolicy.Event.INTERVIEW_TIME);
            }
            if (!holdsBack && acceptsOffer(text, whole) && notAllowed(StopEventPolicy.Event.OFFER, preference)) {
                return Optional.of(StopEventPolicy.Event.OFFER);
            }
            if (acceptsSalaryNumber(text) && notAllowed(StopEventPolicy.Event.SALARY_DECISION, preference)) {
                return Optional.of(StopEventPolicy.Event.SALARY_DECISION);
            }
            if (acceptsDealbreaker(text, preference)) {
                return Optional.of(StopEventPolicy.Event.DEALBREAKER);
            }
        }
        return Optional.empty();
    }

    private static boolean notAllowed(StopEventPolicy.Event event, PreferenceEntity preference) {
        return StopEventPolicy.policyFor(event, preference) != StopEventPolicy.Policy.ALLOW;
    }

    private static boolean isQuestion(String clause) {
        return clause.endsWith("吗") || clause.endsWith("呢") || clause.contains("是否") || clause.contains("请问");
    }

    static boolean promisesTime(String text) {
        return CONCRETE_TIME.matcher(text).find()
                && TIME_AGREE.matcher(text).find()
                && (TIME_CONTEXT.matcher(text).find() || text.contains("准时"));
    }

    /** 接 Offer：回复里说 offer/录用，或在「恭喜通过/决定录用」之后说「愿意加入」「很乐意入职」。 */
    static boolean acceptsOffer(String text, String whole) {
        String lower = whole.toLowerCase();
        boolean offerTopic = lower.contains("offer") || whole.contains("录用") || whole.contains("合同");
        boolean joins = containsAny(text, "愿意加入", "乐意加入", "加入贵司", "加入贵公司", "确认入职", "愿意入职",
                "接受offer", "接受这个offer", "接受录用", "签合同", "签一下合同", "签了合同");
        return joins || (offerTopic && containsAny(text, "接受", "同意", "签"));
    }

    static boolean acceptsSalaryNumber(String text) {
        return SALARY_NUMBER.matcher(text).find()
                && AGREE.matcher(text).find()
                && !containsAny(text, "期望", "希望", "了解下", "构成");
    }

    /** 用户在「其他底线」里写了不接受，AI 却在同一分句里说「可以/没问题」。 */
    static boolean acceptsDealbreaker(String text, PreferenceEntity preference) {
        List<String> dealbreakers = preference == null ? null : preference.getDealbreakers();
        if (dealbreakers == null || dealbreakers.isEmpty() || !AGREE.matcher(text).find()) {
            return false;
        }
        for (String item : dealbreakers) {
            String key = StringUtils.trimToEmpty(item);
            if (key.length() >= 2 && text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
