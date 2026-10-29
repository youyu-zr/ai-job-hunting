package com.maple.ai.job.hunting.utils;

import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 「停下来叫我」：HR 消息里出现哪些事件时，AI 不能自己做主。
 *
 * <p>按具体事件停，不按「意向高低」停。依据见 {@code _research/检索_限流与停下确认与招呼语.md}：
 * 社区同类工具（boss-zhipin-auto-job-hunter、boss-zhipin-codex-skill）都把面试、Offer、薪资谈判、
 * 隐私请求、无法确定语义列为必须人工确认；AI 自报的置信度不可靠，所以这里全部用可检查的规则。</p>
 *
 * <p>每个事件三档：PAUSE 停下叫我 / DECLINE AI 先婉拒或问清（同一会话只一次，第二次自动升级为 PAUSE）
 * / ALLOW 让 AI 处理。疑似骗局与看不懂的内容固定 PAUSE，不给 AI 处理的选项。</p>
 */
public final class StopEventPolicy {

    public enum Event {
        /** 收钱、要证件、拉离平台、刷单：求职者最常被骗的场景。 */
        SCAM_OR_OFF_PLATFORM,
        /** Offer / 录用，要求接受或答复。 */
        OFFER,
        /** 约了具体的面试时间或具体到岗日期。 */
        INTERVIEW_TIME,
        /** HR 报了具体薪资并要你表态。 */
        SALARY_DECISION,
        /** 要微信 / 电话 / 交换联系方式，或要打电话、视频。 */
        CONTACT,
        /** 问婚育、年龄、目前薪资等偏好里没有、AI 不能编的个人信息。 */
        PERSONAL_INFO,
        /** 语音、图片、视频、笔试测评链接等 AI 读不了或不能代做的内容。 */
        UNREADABLE,
        /** HR 要简历（在发简历的地方单独判断）。 */
        RESUME,
        /** AI 的回复答应了用户「其他底线」里写明不接受的条件（只在发送前检查）。 */
        DEALBREAKER
    }

    public enum Policy {PAUSE, DECLINE, ALLOW}

    /** 词前 6 个字内出现否定（如「不收任何培训费」「无需押金」）就不算。单个「无」不算（无锡）。 */
    private static final Pattern NEGATED = Pattern.compile("(不|无需|无须|无任何|免|没有|没)");
    /** 「18k」「8千」「1.5万」这类薪资数字，HR 不写「薪资」二字也算。 */
    // 前面不能是数字：一长串数字时不会从每一位重新试（CodeQL 多项式回溯告警）。
    private static final Pattern MONEY = Pattern.compile("(?<![\\d.])\\d++(\\.\\d++)?(k|千|万|w)|(?<!\\d)\\d{4,6}(元|块)");

    private StopEventPolicy() {
    }

    /** 优先级最高的那个事件（只用于日志和测试；处理时用 {@link #detectAll}）。 */
    public static Optional<Event> detect(String question) {
        return detectAll(question).stream().findFirst();
    }

    /**
     * 一条 HR 消息里所有需要本人处理的事件，按优先级排列。一条消息可能同时
     * 约面试又报薪资，所以要全部列出，处理时取用户设置里最严的那一档。
     * 不含 RESUME（HR 要简历在发简历的地方单独判断）。
     */
    public static List<Event> detectAll(String question) {
        List<Event> events = new ArrayList<>();
        if (StringUtils.isBlank(question)
                || ConversationGateUtil.isRefusalOrSystemMessage(question)
                || ConversationGateUtil.isExplicitHrRejection(question)) {
            return events;
        }
        String text = compact(question).toLowerCase();
        if (isScamOrOffPlatform(text)) {
            events.add(Event.SCAM_OR_OFF_PLATFORM);
        }
        // 签合同/协议与接 Offer 一样是录用承诺，归到 OFFER。
        if (isOfferDecision(text) || isContractDecision(text)) {
            events.add(Event.OFFER);
        }
        if (ConversationGateUtil.requiresExplicitCommitmentConfirmation(question) && !events.contains(Event.OFFER)) {
            events.add(Event.INTERVIEW_TIME);
        }
        if (isSalaryDecision(text)) {
            events.add(Event.SALARY_DECISION);
        }
        if (ConversationGateUtil.isContactExchangeQuestion(question) || isContactRequest(text) || isCallRequest(text)) {
            events.add(Event.CONTACT);
        }
        if (isPersonalInfoQuestion(text)) {
            events.add(Event.PERSONAL_INFO);
        }
        if (isUnreadable(text)) {
            events.add(Event.UNREADABLE);
        }
        return events;
    }

    /** 多个事件里用户设置最严的那个（PAUSE > DECLINE > ALLOW）；同档取优先级高的。 */
    public static Optional<Event> strictest(List<Event> events, PreferenceEntity preference) {
        Event chosen = null;
        for (Event event : events) {
            if (chosen == null || policyFor(event, preference).ordinal() < policyFor(chosen, preference).ordinal()) {
                chosen = event;
            }
        }
        return Optional.ofNullable(chosen);
    }

    /** 用户为这个事件选了哪一档；没选过用默认值。 */
    public static Policy policyFor(Event event, PreferenceEntity preference) {
        PreferenceEntity p = preference == null ? new PreferenceEntity() : preference;
        return switch (event) {
            case SCAM_OR_OFF_PLATFORM, UNREADABLE, DEALBREAKER -> Policy.PAUSE;
            case OFFER -> parse(p.getStopOffer(), Policy.PAUSE);
            case INTERVIEW_TIME -> parse(p.getStopInterview(), Policy.PAUSE);
            case SALARY_DECISION -> parse(p.getStopSalary(), Policy.PAUSE);
            // 旧开关「允许 AI 在回复里发出我的联系方式」打开过的用户，沿用为「让 AI 处理」。
            case CONTACT -> parse(p.getStopContact(), p.isContactShareE() ? Policy.ALLOW : Policy.PAUSE);
            case PERSONAL_INFO -> parse(p.getStopPersonal(), Policy.PAUSE);
            case RESUME -> parse(p.getStopResume(), Policy.ALLOW);
        };
    }

    /**
     * AI 能不能把「我自己的」微信号/手机号写给 HR。
     * 要求两件事同时成立：联系方式这一档是「让 AI 处理」，并且 contactShareE 为 true。
     * 新版偏好页选「让 AI 处理」时会同时把 contactShareE 设为 true；
     * 旧版里「选了让 AI 处理、但没勾允许写出号码」的用户，contactShareE 仍是 false，
     * 他们当初的选择是「AI 可以回应，但不写号码」，这里继续照这个办，等本人在页面上确认后才放开。
     */
    public static boolean contactNumbersAllowed(PreferenceEntity preference) {
        return preference != null
                && preference.isContactShareE()
                && policyFor(Event.CONTACT, preference) == Policy.ALLOW;
    }

    /** DECLINE 档要发的那一句。只问清或婉拒，不做任何承诺。 */
    public static String declineText(Event event) {
        return switch (event) {
            case INTERVIEW_TIME -> "这个时间我先确认下自己的安排，稍后给您准确答复。";
            case OFFER -> "非常感谢认可！这个我需要认真考虑一下，尽快给您答复。";
            case SALARY_DECISION -> "薪资这块想先了解下具体构成，比如底薪、绩效和年终、社保公积金基数，方便说一下吗？";
            case CONTACT -> "我们先在这边沟通可以吗？聊得差不多了我再加您。";
            case PERSONAL_INFO -> "这个方便的话我们面谈时再细聊。";
            case RESUME -> "我整理下最新版简历，稍后发您。";
            case SCAM_OR_OFF_PLATFORM, UNREADABLE, DEALBREAKER -> "";
        };
    }

    /** PAUSE 时先替你回的过渡话；疑似骗局不回。 */
    public static String holdText(Event event) {
        if (event == Event.SCAM_OR_OFF_PLATFORM) {
            return "";
        }
        return "收到，我确认一下，尽快回复您。";
    }

    /** 给用户看的事件名。 */
    public static String label(Event event) {
        return switch (event) {
            case SCAM_OR_OFF_PLATFORM -> "疑似收费/要证件/拉你离开平台";
            case OFFER -> "Offer / 录用答复";
            case INTERVIEW_TIME -> "约了具体面试或到岗时间";
            case SALARY_DECISION -> "HR 报了薪资要你表态";
            case CONTACT -> "要微信/电话或要通话";
            case PERSONAL_INFO -> "问了个人信息（婚育/年龄/目前薪资等）";
            case UNREADABLE -> "语音/图片/笔试链接等 AI 处理不了的内容";
            case RESUME -> "要简历";
            case DEALBREAKER -> "涉及你写明不接受的条件";
        };
    }

    private static Policy parse(String value, Policy fallback) {
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        try {
            return Policy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static boolean isScamOrOffPlatform(String text) {
        if (containsUnnegated(text, "培训费", "押金", "服装费", "体检费", "报名费", "保证金", "中介费",
                "入职费", "办卡费", "资料费", "工本费", "缴费", "先交", "预付", "转账给", "转给我", "扫码付",
                "自己垫付", "个人垫付")) {
            return true;
        }
        if (containsUnnegated(text, "身份证号", "身份证照片", "身份证正反面", "银行卡号", "银行卡照片",
                "银行卡密码", "验证码发我", "验证码告诉")) {
            return true;
        }
        // 拉你去别的软件：要的是「你去下载/注册」，不是岗位职责里提到 app 或软件。
        if (containsAny(text, "下载我们的", "下载一下", "下载这个", "先下载", "去下载", "安装一下", "装一下",
                "注册一下", "先注册", "去注册", "注册个账号", "注册账号")
                && containsAny(text, "app", "软件", "客户端", "平台", "账号")) {
            return true;
        }
        if (containsAny(text, "加群", "进群", "扫码进", "qq群", "群号", "拉你进")) {
            return true;
        }
        return containsAny(text, "刷单", "返利", "点赞赚", "打字员", "垫资", "做任务赚");
    }

    private static boolean isOfferDecision(String text) {
        return (text.contains("offer") || text.contains("录用") || text.contains("入职通知"))
                && containsAny(text, "接受", "同意", "确认", "答复", "考虑", "发给你", "发您", "发你");
    }

    private static boolean isContractDecision(String text) {
        return (text.contains("合同") || text.contains("协议"))
                && containsAny(text, "签一下", "签了", "签署", "签字", "签个", "接受", "同意", "确认", "承诺");
    }

    private static boolean isSalaryDecision(String text) {
        // 「我们给到18k，能接受吗」不带「薪资」二字也算：数字带 k/千/万 本身就是薪资。
        boolean hasMoney = MONEY.matcher(text).find();
        boolean hasCompensation = hasMoney || containsAny(text, "薪资", "工资", "薪酬", "月薪", "年薪", "底薪", "待遇");
        boolean hasNumber = hasMoney || Pattern.compile("\\d").matcher(text).find()
                || containsAny(text, "千", "万");
        boolean asksAcceptance = containsAny(text, "接受吗", "能接受", "可以接受", "是否接受", "能否接受",
                "可以吗", "行吗", "合适吗", "考虑吗", "确认薪资", "确认工资");
        return hasCompensation && hasNumber && asksAcceptance;
    }

    private static boolean isContactRequest(String text) {
        return containsAny(text, "加个微信", "加微信", "加下微信", "加一下微信", "留个微信", "微信号多少", "你的微信",
                "留个电话", "电话号码多少", "手机号多少", "你的手机号", "你的电话", "加个qq", "留个联系方式");
    }

    private static boolean isCallRequest(String text) {
        return containsAny(text, "电话聊", "电话沟通一下", "打电话给你", "打个电话", "给你打电话", "方便接电话",
                "语音通话", "视频通话", "视频聊", "语音聊");
    }

    private static boolean isPersonalInfoQuestion(String text) {
        // 只收问「你本人」的说法；「生育保险」「可解决户口」「期望薪资多少」不算（后者偏好里有区间，AI 能答）。
        return containsAny(text, "婚否", "结婚了吗", "结婚没", "已婚还是未婚", "有没有孩子", "有孩子吗",
                "生育计划", "要孩子", "打算要", "多大了", "几岁", "年龄多大", "今年多大", "现在薪资", "目前薪资",
                "当前薪资", "上家薪资", "之前薪资", "现在工资", "目前工资", "上份工资", "现在月薪", "目前月薪",
                "户口在哪", "户籍在哪", "哪里人", "籍贯", "身体状况", "有没有病");
    }

    private static boolean isUnreadable(String text) {
        // 要你现在去做的才算；「流程是一轮笔试两轮面试」「我们没有笔试」不算。
        return containsAny(text, "[语音]", "[图片]", "[视频]", "笔试链接", "笔试题", "完成笔试", "做一下笔试",
                "做个笔试", "做下笔试", "先笔试", "这个笔试", "这份笔试", "做笔试", "在线笔试", "测评链接", "在线测试", "答题链接", "做个测试",
                "做一下测评", "做下测评", "填写问卷", "问卷链接");
    }

    private static boolean containsUnnegated(String text, String... terms) {
        for (String term : terms) {
            int index = text.indexOf(term);
            while (index >= 0) {
                String before = text.substring(Math.max(0, index - 6), index)
                        .replaceAll("没问题|不错|不过|不好意思|免费", "");
                // 「无押金」：紧挨着的「无」算否定；隔开的「无」（无锡基地需交押金）不算。
                if (!NEGATED.matcher(before).find() && !before.endsWith("无")) {
                    return true;
                }
                index = text.indexOf(term, index + term.length());
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

    private static String compact(String text) {
        return StringUtils.defaultString(text).replaceAll("\\s+", "");
    }
}
