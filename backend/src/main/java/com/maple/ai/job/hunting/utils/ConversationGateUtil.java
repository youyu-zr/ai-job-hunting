package com.maple.ai.job.hunting.utils;

import org.apache.commons.lang3.StringUtils;

/**
 * Deterministic gates for events that must not be answered or accepted by AI.
 */
public final class ConversationGateUtil {

    private ConversationGateUtil() {
    }

    /**
     * Substantive HR messages belong to the conversational model. Only empty
     * payloads and platform protocol noise are silently suppressed upstream.
     */
    public static boolean shouldDelegateToAi(String question) {
        return StringUtils.isNotBlank(question) && !isNoisyProtocolPayload(question);
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
        if (text.matches(".*1[3-9]\\d{9}.*")) {
            return true;
        }

        boolean hasContactNoun = text.contains("联系方式")
                || text.contains("手机号")
                || text.contains("手机号码")
                || text.contains("电话号码")
                || text.contains("微信号")
                || text.contains("微信");
        boolean hasContactValue = hasContactNoun
                && text.matches(".*(联系方式|手机号|手机号码|电话号码|微信号|微信|电话)(?:[:：=]|是|为|[\\s_-])*[A-Za-z0-9_.-]{3,}.*");
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
                && text.matches(".*(平台|BOSS|系统).*(投递|提交|上传).*")) {
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
        boolean hasInterview = text.matches(".*(面试|面谈|初试|复试|试岗|视频面|线上面|线下面|约面).*");
        boolean hasConcreteTime = text.matches(".*(今天|明天|后天|周一|周二|周三|周四|周五|周六|周日|\\d{1,2}月\\d{1,2}日|\\d{1,2}点|上午|下午|晚上).*");
        boolean hasArrangement = text.matches(".*(面试邀请|面试卡片|请点击接受|已经为您安排|面试地址|到公司参加).*");
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
