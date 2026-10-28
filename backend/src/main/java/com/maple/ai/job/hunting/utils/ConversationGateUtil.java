package com.maple.ai.job.hunting.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic gates for events that must not be answered or accepted by AI.
 */
public final class ConversationGateUtil {

    private static final Pattern MOBILE_NUMBER_PATTERN = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("\\d{1,2}月\\d{1,2}[日号]?");
    private static final Pattern YEAR_MONTH_DAY_PATTERN =
            Pattern.compile("\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}(?:日|号)?");
    private static final Pattern CLOCK_TIME_PATTERN = Pattern.compile("\\d{1,2}[:：]\\d{2}");
    private static final Pattern CHINESE_TIME_PATTERN = Pattern.compile("\\d{1,2}点(?:\\d{1,2}分)?");

    private ConversationGateUtil() {
    }

    /**
     * Substantive HR messages belong to the conversational model. Only empty
     * payloads and platform protocol noise are silently suppressed upstream.
     */
    public static boolean shouldDelegateToAi(String question) {
        return StringUtils.isNotBlank(question) && !isNoisyProtocolPayload(question);
    }

    /**
     * 判断消息是否更像营销引流，而不是岗位沟通。
     *
     * <p>单独出现“推广”“销售”“市场”等岗位词不能触发静默；
     * 需要同时出现扫码、进群、注册、付费、返佣等引流或转化动作，
     * 才认为当前消息默认不值得由求职者自动回复。</p>
     */
    public static boolean isPromotionalMessage(String question) {
        if (StringUtils.isBlank(question)) {
            return false;
        }
        String text = compact(question);
        if (isHardPromotionalRisk(text)) {
            return true;
        }
        boolean promotionContext = containsUnnegatedAny(text,
                "推广", "宣传", "营销", "广告", "课程", "培训", "代理", "加盟",
                "招商", "返佣", "拉新", "裂变", "招聘广告", "资料领取", "获取资料", "项目");
        boolean funnelAction = containsUnnegatedAny(text,
                "扫码进群", "扫码加群", "扫码领取", "扫码报名", "扫码下载", "扫码了解",
                "扫描二维码", "进群领取", "加群领取", "注册后", "注册即可", "注册领取",
                "下载后", "点击链接", "填写邀请码", "邀请好友", "成为代理", "加入社群",
                "添加客服", "加客服", "加老师", "联系导师", "添加微信", "加微信",
                "微信领取", "领取课程资料", "关注公众号",
                "请扫码", "请注册", "请下载", "请进群", "请加群", "请领取",
                "报名即可", "报名领取", "付款后", "缴费后", "先充值");
        if (!promotionContext || !funnelAction) {
            return false;
        }
        boolean jobDutyContext = containsUnnegatedAny(text,
                "岗位职责", "工作职责", "日常工作", "主要工作", "工作内容", "负责");
        boolean candidateDirectedAction = containsUnnegatedAny(text,
                "请你", "请您", "应聘者", "求职者", "领取资料", "填写邀请码",
                "添加客服", "加客服", "微信领取", "领取课程资料",
                "邀请好友", "成为代理");
        return !jobDutyContext || candidateDirectedAction;
    }

    /**
     * 付费、刷单、传销、账号交易等硬风险不受“允许营销回复”选项影响。
     */
    public static boolean isHardPromotionalRisk(String question) {
        if (StringUtils.isBlank(question)) {
            return false;
        }
        String text = compact(question);
        boolean paymentOrTrainingRisk = containsUnnegatedAny(text,
                "先交费", "先缴费", "培训贷", "押金", "保证金",
                "培训费", "设备费", "报名费", "付费培训", "购买课程", "付款后");
        boolean membershipFeeRisk = java.util.Arrays.stream(
                        text.split("[，,。.;；!！?？\\n\\r]"))
                .anyMatch(clause -> containsUnnegatedAny(clause, "入会费")
                        && containsUnnegatedAny(clause,
                        "缴纳", "交纳", "先交", "先缴", "支付", "收取", "交费", "缴费"));
        boolean pyramidOrCommissionRisk = containsUnnegatedAny(text,
                "传销", "拉人头", "团队裂变", "层级返利", "邀请好友返佣",
                "发展下线", "代理加盟", "招商加盟");
        boolean fraudOrAccountRisk = containsUnnegatedAny(text,
                "刷单", "刷流水", "账号租借", "租账号", "账号买卖", "买卖账号",
                "购买账号", "账号交易", "代实名", "博彩", "代充");
        return paymentOrTrainingRisk || membershipFeeRisk || pyramidOrCommissionRisk || fraudOrAccountRisk;
    }

    private static boolean containsUnnegatedAny(String text, String... terms) {
        for (String term : terms) {
            if (JobRiskKeywordUtil.findExcludeKeyword(text, List.of(term)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern OUTBOUND_MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d(?:[\\s-]?\\d){8}(?!\\d)");
    private static final Pattern OUTBOUND_EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern OUTBOUND_WECHAT = Pattern.compile(
            "(?i)(?:微信号?|vx|wx|v信|weixin|wechat)\\s*[:：是为]?\\s*[A-Za-z][-_A-Za-z0-9]{5,19}");
    private static final Pattern OUTBOUND_QQ = Pattern.compile("(?i)qq\\s*[:：号是为]?\\s*[1-9]\\d{4,11}");

    /**
     * AI 准备发出去的回复里是否带了具体联系方式（手机号、邮箱、微信号、QQ）。
     * 只看具体号码/账号；「可以加微信沟通」这类说法不算。
     */
    public static boolean containsOutboundContact(String answer) {
        if (StringUtils.isBlank(answer)) {
            return false;
        }
        return OUTBOUND_MOBILE.matcher(answer).find()
                || OUTBOUND_EMAIL.matcher(answer).find()
                || OUTBOUND_WECHAT.matcher(answer).find()
                || OUTBOUND_QQ.matcher(answer).find();
    }

    public static boolean isContactExchangeQuestion(String question) {
        if (StringUtils.isBlank(question)) {
            return false;
        }
        String text = compact(question);
        if (text.contains("&lt;/phone&gt;")
                || text.contains("</phone>")
                || text.contains("&lt;/copy&gt;")
                || text.contains("</copy>")) {
            return true;
        }
        if (MOBILE_NUMBER_PATTERN.matcher(text).find()) {
            return true;
        }

        boolean hasContactNoun = text.contains("联系方式")
                || text.contains("手机号")
                || text.contains("手机号码")
                || text.contains("电话号码")
                || text.contains("微信号")
                || text.contains("微信");
        boolean hasContactValue = hasContactNoun && hasContactValueAfterLabel(text);
        if (hasContactValue) {
            return true;
        }

        // A generic mention such as "电话沟通" or "后面加微信" is still part
        // of the AI conversation. Gate only an explicit request to exchange
        // or provide a contact channel.
        return text.contains("交换微信")
                || text.contains("交换联系方式")
                || text.contains("交换电话")
                || text.contains("交换手机号")
                || text.contains("交换手机号码")
                || text.contains("换微信")
                || text.contains("换个微信")
                || text.contains("换电话")
                || text.contains("换个电话")
                || text.contains("留微信")
                || text.contains("留个微信")
                || text.contains("留电话")
                || text.contains("留个电话")
                || text.contains("发微信")
                || text.contains("发个微信")
                || text.contains("发电话")
                || text.contains("发个电话")
                || text.contains("发联系方式")
                || text.contains("发个联系方式")
                || text.contains("给我微信")
                || text.contains("给我微信号")
                || text.contains("给我电话")
                || text.contains("给我联系方式")
                || text.contains("给我手机号")
                || text.contains("给我手机号码")
                || text.contains("给我电话号码")
                || text.contains("把微信给我")
                || text.contains("把电话给我")
                || text.contains("把联系方式给我")
                || text.contains("提供微信")
                || text.contains("提供电话")
                || text.contains("提供联系方式")
                || text.contains("发给我") && hasContactNoun
                || text.contains("想要") && hasContactNoun
                || text.contains("需要") && hasContactNoun
                || text.contains("电话号码多少")
                || text.contains("手机号多少")
                || text.contains("手机号码多少")
                || text.contains("电话多少")
                || text.contains("您的电话多少")
                || text.contains("你的电话多少")
                || text.contains("您电话多少")
                || text.contains("你电话多少")
                || text.contains("加我微信")
                || text.contains("加我微信号")
                || text.contains("把你的微信发我")
                || text.contains("把您微信发我")
                || text.contains("把你的电话发我")
                || text.contains("把您电话发我")
                || text.contains("把你的联系方式发我")
                || text.contains("把您联系方式发我");
    }

    public static String humanHandoffCategory(String question) {
        if (StringUtils.isBlank(question)
                || isRefusalOrSystemMessage(question)
                || isExplicitHrRejection(question)
                || isConversationClosingMessage(question)) {
            return "";
        }
        if (requiresExplicitCommitmentConfirmation(question)) {
            String text = compact(question).toLowerCase();
            if (isStartDateDecision(text) && hasConcreteDateOrTime(text)) {
                return "START_DATE";
            }
            return "INTERVIEW";
        }
        return "";
    }

    public static boolean requiresExplicitCommitmentConfirmation(String question) {
        if (StringUtils.isBlank(question)) {
            return false;
        }
        String text = compact(question).toLowerCase();
        if (isInterviewDecision(text) && hasConcreteDateOrTime(text)) {
            return true;
        }
        if (isStartDateDecision(text) && hasConcreteDateOrTime(text)) {
            return true;
        }
        if ((text.contains("合同") || text.contains("协议"))
                && containsAny(text, "签", "接受", "同意", "确认", "承诺")) {
            return true;
        }
        return (text.contains("offer") || text.contains("录用") || text.contains("入职通知"))
                && containsAny(text, "接受", "同意", "确认", "答复", "承诺");
    }

    public static boolean isKnownPlatformBodyType(Integer bodyType) {
        return bodyType != null && List.of(4, 8, 12, 14).contains(bodyType);
    }

    public static boolean isConcreteInterviewOrCommitment(String question) {
        return requiresExplicitCommitmentConfirmation(question);
    }

    private static boolean isLocationAcceptanceDecision(String question) {
        if (isLocationDecisionQuestion(question)) {
            return true;
        }
        String text = compact(question);
        boolean hasLocation = containsAny(text, "工作地点", "办公地点", "工作地址", "办公地址", "上班地点", "驻场地点");
        boolean asksForAcceptance = containsAny(text, "是否接受", "能否接受", "可以接受吗", "能接受吗",
                "你接受吗", "您接受吗", "你能接受", "您能接受", "愿意接受", "愿意去", "能去吗",
                "可以到吗", "方便去吗", "通勤能接受吗");
        return hasLocation && asksForAcceptance;
    }

    private static boolean isInterviewDecision(String question) {
        String text = compact(question);
        String[] clauses = text.split("[，,。；;！？!?]");
        for (String clause : clauses) {
            String segment = compact(clause);
            boolean hasInterview = containsAny(segment, "面试", "面谈", "初试", "复试", "一面", "二面", "三面",
                    "试岗", "视频面", "线上面", "线下面", "约面", "邀约");
            if (!hasInterview) {
                continue;
            }
            if (containsAny(segment, "面试已取消", "面试已结束", "面试超时未接受", "不安排面试")) {
                continue;
            }
            boolean explicitArrangement = containsAny(segment, "面试邀请", "面试卡片", "面试时间", "面试安排",
                    "面试地点", "接受邀约", "请点击接受", "请确认面试", "确认面试时间", "请参加面试",
                    "邀请您参加", "邀请你参加", "约您面试", "约你面试", "安排您参加", "安排你参加",
                    "方便参加面试", "是否参加面试", "能否参加面试", "愿意参加面试");
            if (explicitArrangement) {
                return true;
            }
            if (!containsAny(segment, "面试流程", "面试形式", "面试轮次")
                    && hasConcreteDateOrTime(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCompensationDecision(String text) {
        boolean hasCompensation = containsAny(text, "薪资", "工资", "薪酬", "月薪", "年薪", "底薪", "待遇");
        if (!hasCompensation) {
            return false;
        }
        return containsAny(text, "是否接受", "能否接受", "可否接受", "可以接受吗", "能接受吗",
                "接受吗", "薪资请确认", "工资请确认", "确认薪资", "确认工资",
                "回复确认薪资", "回复确认工资", "薪资合适吗", "工资合适吗");
    }

    private static boolean isStartDateDecision(String text) {
        boolean hasStartTerm = containsAny(text, "到岗", "入职", "报到");
        boolean asksForTiming = containsAny(text, "什么时候", "何时", "哪天", "几号", "最快", "最早", "最晚",
                "时间", "日期", "确认", "可以", "能否", "方便") || hasConcreteDateOrTime(text);
        return hasStartTerm && asksForTiming;
    }

    public static boolean hasConcreteDateOrTime(String text) {
        return containsAny(text, "今天", "明天", "后天", "周一", "周二", "周三", "周四", "周五", "周六", "周日",
                "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "上午", "下午", "晚上")
                || MONTH_DAY_PATTERN.matcher(text).find()
                || YEAR_MONTH_DAY_PATTERN.matcher(text).find()
                || CLOCK_TIME_PATTERN.matcher(text).find()
                || CHINESE_TIME_PATTERN.matcher(text).find();
    }

    private static boolean hasContactValueAfterLabel(String text) {
        String[] labels = {"联系方式", "手机号", "手机号码", "电话号码", "微信号", "微信", "电话"};
        for (String label : labels) {
            int labelAt = text.indexOf(label);
            while (labelAt >= 0) {
                int index = labelAt + label.length();
                int separatorCount = 0;
                while (index < text.length() && separatorCount < 16 && isContactSeparator(text.charAt(index))) {
                    index++;
                    separatorCount++;
                }
                int valueStart = index;
                while (index < text.length() && isContactValueCharacter(text.charAt(index))) {
                    index++;
                }
                if (index - valueStart >= 3) {
                    return true;
                }
                labelAt = text.indexOf(label, labelAt + 1);
            }
        }
        return false;
    }

    private static boolean isContactSeparator(char ch) {
        return ch == ':' || ch == '：' || ch == '=' || ch == '_' || ch == '-' || ch == '是' || ch == '为'
                || Character.isWhitespace(ch);
    }

    private static boolean isContactValueCharacter(char ch) {
        return ch >= 'A' && ch <= 'Z'
                || ch >= 'a' && ch <= 'z'
                || ch >= '0' && ch <= '9'
                || ch == '_'
                || ch == '.'
                || ch == '-';
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRefusalOrSystemMessage(String question) {
        if (StringUtils.isBlank(question)) {
            return true;
        }
        String text = compact(question);
        if (text.contains("对方拒绝了您的发送请求")
                || text.contains("附件简历已发送给对方")
                || text.contains("简历已发送给对方")
                || text.contains("撤回了一条消息")
                || text.contains("职位已下线")
                || text.contains("岗位已下线")
                || text.contains("已停止招聘")
                || text.contains("已招满")
                || text.contains("系统消息")
                || text.contains("面试已取消")
                || text.contains("面试已结束")
                || text.contains("面试超时未接受")) {
            return true;
        }
        return text.contains("感谢您的投递")
                && (text.contains("遗憾")
                || text.contains("不匹配")
                || text.contains("暂不")
                || text.contains("不合适")
                || text.contains("未能通过"));
    }

    /**
     * HR has acknowledged the submission and closed this exchange for now.
     * A message that also asks for a resume remains actionable and is handled
     * by the resume gate before this check is applied.
     */
    public static boolean isConversationClosingMessage(String question) {
        if (StringUtils.isBlank(question)) {
            return false;
        }
        String text = compact(question);
        if (text.contains("简历")
                && containsAny(text, "平台", "BOSS", "系统")
                && containsAny(text, "投递", "提交", "上传")) {
            return false;
        }
        if (text.contains("对话结束") || text.contains("会话结束") || text.contains("本轮结束")) {
            return true;
        }
        boolean waitingForEvaluation = text.contains("后续事宜")
                && (text.contains("耐心等待")
                || text.contains("等待评估")
                || text.contains("团队评估")
                || text.contains("祝好"));
        return waitingForEvaluation
                && (text.contains("感谢您的提交")
                || text.contains("感谢您的配合")
                || text.contains("谢谢您的配合")
                || text.contains("感谢您的投递"));
    }


    public static boolean isExplicitHrRejection(String question) {
        if (StringUtils.isBlank(question)) {
            return false;
        }
        String text = compact(question);
        if (text.contains("对方拒绝了您的发送请求")
                || text.contains("附件简历已发送给对方")
                || text.contains("简历已发送给对方")
                || text.contains("撤回了一条消息")
                || text.contains("系统消息")) {
            return false;
        }
        if (text.contains("感谢您的投递")
                && (text.contains("遗憾")
                || text.contains("不匹配")
                || text.contains("暂不")
                || text.contains("不合适")
                || text.contains("未能通过"))) {
            return true;
        }
        return text.contains("不太合适")
                || text.contains("不合适哦")
                || text.contains("暂时不合适")
                || text.contains("暂不合适")
                || text.contains("不太匹配")
                || text.contains("暂不匹配")
                || text.contains("暂时不匹配")
                || text.contains("不匹配")
                || text.contains("不是很匹配")
                || text.contains("未能通过")
                || text.contains("不符合要求")
                || text.contains("不符合岗位")
                || text.contains("已招满")
                || text.contains("招满了")
                || text.contains("祝你早日找到")
                || text.contains("祝您早日找到");
    }


    public static boolean isLocationDecisionQuestion(String question) {
        if (StringUtils.isBlank(question)) {
            return false;
        }
        String text = compact(question);
        return text.contains("您是否接受此工作地点")
                || text.contains("是否接受此工作地点")
                || text.contains("接受此工作地点")
                || text.contains("接受该工作地点")
                || text.contains("接受这个工作地点")
                || (text.contains("jobLocationRefactor")
                && (text.contains("locationDesc") || text.contains("addressNum"))
                && (text.contains("可以接受") || text.contains("暂不考虑") || text.contains("工作地点")))
                || (text.contains("bosszp://bosszhipin.app/openwith?type=sendaction")
                && (text.contains("可以接受") || text.contains("暂不考虑")));
    }

    public static boolean isNoisyProtocolPayload(String question) {
        if (StringUtils.isBlank(question)) {
            return false;
        }
        if (isLocationDecisionQuestion(question)
                || isContactExchangeQuestion(question)
                || isInterviewSchedulingQuestion(question)) {
            return false;
        }
        String text = question.trim();
        if (text.contains("img.bosszhipin.com") && text.contains("webp")) {
            return true;
        }
        int controlCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t') {
                controlCount++;
            }
        }
        if (controlCount >= 3 && (text.contains("bosszp://")
                || text.contains("img.bosszhipin.com")
                || text.contains("webp")
                || text.contains("sendaction"))) {
            return true;
        }
        return controlCount >= 8 && text.length() > 80;
    }


    public static boolean isRejectionAnswer(String answer) {
        if (StringUtils.isBlank(answer)) {
            return false;
        }
        String text = compact(answer);
        return text.contains("暂时不考虑")
                || text.contains("不太匹配")
                || text.contains("不匹配")
                || text.contains("不合适")
                || text.contains("不是很匹配")
                || text.contains("求职方向不符")
                || text.contains("求职方向不太匹配")
                || text.contains("谢谢告知");
    }

    public static boolean isExplicitInterviewArrangementQuestion(String question) {
        if (StringUtils.isBlank(question)) {
            return false;
        }
        String text = compact(question);
        boolean hasInterview = containsAny(text, "面试", "面谈", "初试", "复试", "试岗",
                "视频面", "线上面", "线下面", "约面");
        boolean hasConcreteTime = hasConcreteDateOrTime(text);
        boolean hasArrangement = containsAny(text, "面试邀请", "面试卡片", "请点击接受",
                "已经为您安排", "面试地址", "到公司参加");
        return hasInterview && (hasConcreteTime || hasArrangement);
    }

    public static boolean isInterviewSchedulingQuestion(String question) {
        if (StringUtils.isBlank(question)) {
            return false;
        }
        String text = compact(question);
        if (isRefusalOrSystemMessage(text)
                && !text.contains("面试")
                && !text.contains("约面")
                && !text.contains("邀约")) {
            return false;
        }
        if (text.contains("面试超时未接受")
                || text.contains("面试已取消")
                || text.contains("面试已结束")) {
            return false;
        }
        return text.contains("面试")
                || text.contains("面试卡片")
                || text.contains("面试信息")
                || text.contains("面试邀约")
                || text.contains("约面")
                || text.contains("约个时间")
                || text.contains("约时间")
                || text.contains("安排面试")
                || text.contains("接受邀约")
                || text.contains("请点击接受");
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }
}
