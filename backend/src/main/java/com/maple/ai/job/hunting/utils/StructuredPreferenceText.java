package com.maple.ai.job.hunting.utils;

import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 把偏好页 v3 的结构化字段写成大白话，交给岗位筛选、消息分诊和聊天回复三处的模型。
 *
 * <p>两种口径：</p>
 * <ul>
 *   <li>{@link #forScreening}：给岗位筛选看，包含薪资底线和屏蔽公司（只用于判断，不会发给 HR）。
 *   硬条件和软偏好分开写，软偏好不能单独作为不投的理由。</li>
 *   <li>{@link #forReply}：给聊天回复、分诊和高意向复核看，不含底线、屏蔽公司和详细住址，
 *   并写明「报区间上半段、不答应任何数字」。本人微信号只给聊天回复，而且要本人允许。</li>
 * </ul>
 * 用户没填的项不写，不替用户假设；一项都没填时返回空串。
 */
public final class StructuredPreferenceText {

    private StructuredPreferenceText() {
    }

    public static String forScreening(PreferenceEntity p) {
        if (p == null) {
            return "";
        }
        // 硬条件在前、软偏好在后，避免模型拿期望薪资或方向当硬过滤。
        StringBuilder hard = new StringBuilder();
        if (p.getSalaryFloorK() != null && p.getSalaryFloorK() > 0) {
            line(hard, "薪资底线（硬条件）", "岗位写明的月薪上限低于 " + p.getSalaryFloorK() + "K 时不投；没写薪资时不要因此排除");
        }
        list(hard, "坚决不做（硬条件）", p.getAvoidTags());
        list(hard, "其他底线（硬条件，岗位明确写了才算）", p.getDealbreakers());
        list(hard, "不投的公司（硬条件，含关联公司）", p.getBlockedCompanies());
        StringBuilder soft = new StringBuilder();
        commute(soft, p);
        salaryRange(soft, p);
        list(soft, "想做的方向", p.getWantTags());
        if (soft.length() > 0) {
            soft.insert(0, "以下是软偏好，只用来判断匹配程度，不能单独作为不投的理由：\n");
        }
        return hard.append(soft).toString();
    }

    /** 聊天回复用：本人允许时带上微信号。 */
    public static String forReply(PreferenceEntity p) {
        return forReply(p, true);
    }

    /** includeContact=false：分诊和高意向复核用不到号码，不给。 */
    public static String forReply(PreferenceEntity p, boolean includeContact) {
        if (p == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        // 规则行放在数字前面：分诊偏好过长被截断时先丢后面的内容，不能只剩区间、丢了约束。
        if (hasSalary(p)) {
            // 先报的数会成为后面谈判的基准，所以报区间的上半段；最终答应多少由本人决定。
            line(out, "谈薪规则", "HR 问期望薪资时报 " + upperHalf(p)
                    + "，语气自然、不卑不亢；绝不说底线，也不主动降价。"
                    + "岗位标的薪资上限比这个低时，不要硬报这个数，改说「按岗位范围靠上一些，具体可以再谈」。"
                    + "HR 报出的数字偏低时，先表达希望能再往上谈，并问清薪资构成（底薪、绩效、年终、社保公积金基数）；"
                    + "任何具体数字都不答应也不拒绝，留给本人确认");
        }
        salaryRange(out, p);
        // 回复口径只给城市，不给区/街道/地铁站，免得 AI 对 HR 说出住址。
        if (StringUtils.isNotBlank(p.getHomeCity())) {
            line(out, "所在城市", StringUtils.trimToEmpty(p.getHomeCity()));
        }
        remoteAndRelocate(out, p);
        list(out, "想做的方向", p.getWantTags());
        list(out, "坚决不做", p.getAvoidTags());
        list(out, "其他底线（HR 提到时可以礼貌问清，不要答应）", p.getDealbreakers());
        if (StringUtils.isNotBlank(p.getArrivalTime())) {
            line(out, "最快到岗", p.getArrivalTime() + "（HR 问到岗时间按这个回答，不说具体日期）");
        }
        if (includeContact) {
            contact(out, p);
        }
        // 用户一项都没填时返回空串，保持旧用户的 prompt 不变。
        if (out.length() > 0) {
            line(out, "个人信息", "婚育、年龄、目前薪资、身体状况等简历和偏好里没有的信息，不要编，也不要替本人回答");
        }
        return out.toString();
    }

    private static void commute(StringBuilder out, PreferenceEntity p) {
        String home = (StringUtils.trimToEmpty(p.getHomeCity()) + " " + StringUtils.trimToEmpty(p.getHomeArea())).trim();
        if (StringUtils.isNotBlank(home)) {
            String limit = p.getCommuteMaxMinutes() != null && p.getCommuteMaxMinutes() > 0
                    ? "，单程通勤上限约 " + p.getCommuteMaxMinutes() + " 分钟（按公共交通粗略估算；岗位地址不清楚时不要因此排除）"
                    : "";
            line(out, "住在", home + limit);
        }
        remoteAndRelocate(out, p);
    }

    private static void remoteAndRelocate(StringBuilder out, PreferenceEntity p) {
        if (Boolean.TRUE.equals(p.getRemoteOk())) {
            line(out, "远程", "接受远程岗位");
        }
        // 只写「接受」：复选框没勾不等于用户明确不接受，不能变成硬条件。
        if (Boolean.TRUE.equals(p.getRelocateOk())) {
            line(out, "外地", "接受去外地工作");
        }
    }

    /**
     * 本人允许 AI 发号码时（见 {@link StopEventPolicy#contactNumbersAllowed}），把偏好里的微信号交给聊天 AI。
     * 注意：简历原文里的号码 AI 总是看得到，真正防止误发的是发送前的号码兜底
     * （JobSeekerClonedService.containsDisallowedContact），这里只是让 AI 知道该给哪个微信号。
     */
    private static void contact(StringBuilder out, PreferenceEntity p) {
        if (!StopEventPolicy.contactNumbersAllowed(p)) {
            return;
        }
        // 不把真实号码给模型：模型只写占位符，发送前由服务端检查后替换（ConversationGateUtil.fillOwnContact）。
        String slots = StringUtils.isNotBlank(p.getWechatId())
                ? "HR 要微信时写「" + ConversationGateUtil.OWN_WECHAT_PLACEHOLDER + "」，要电话时写「"
                + ConversationGateUtil.OWN_PHONE_PLACEHOLDER + "」"
                : "HR 要电话时写「" + ConversationGateUtil.OWN_PHONE_PLACEHOLDER + "」；HR 要微信时说可以先加电话号码搜到";
        line(out, "联系方式", slots + "，系统会自动换成本人号码。不要自己写出任何号码；HR 没要时不要主动给");
    }

    /**
     * 先报的数会成为后面谈判的基准，所以报区间的上半段。
     * 12-18K → 15-18K；8-9K、10-11K 这种窄区间直接报 9K、11K（不报「9-9K」）；
     * 只填下限 12K → 12K 以上；只填上限 18K → 18K 左右。
     */
    static String upperHalf(PreferenceEntity p) {
        int[] r = range(p);
        int min = r[0];
        int max = r[1];
        if (min > 0 && max > 0) {
            int mid = (int) Math.ceil((min + max) / 2.0);
            // 报的下限不能低于本人底线（底线在区间内时）：比如 8-20、底线 18，报 18-20K 而不是 14-20K。
            Integer floor = p.getSalaryFloorK();
            if (floor != null && floor > mid && floor <= max) {
                mid = floor;
            }
            return mid >= max ? max + "K" : mid + "-" + max + "K";
        }
        if (max > 0) {
            return max + "K 左右";
        }
        return min + "K 以上";
    }

    /** [下限, 上限]，没填的是 0；用户把上下限填反了就换过来。 */
    private static int[] range(PreferenceEntity p) {
        int min = p.getSalaryMinK() == null ? 0 : Math.max(0, p.getSalaryMinK());
        int max = p.getSalaryMaxK() == null ? 0 : Math.max(0, p.getSalaryMaxK());
        if (min > 0 && max > 0 && min > max) {
            return new int[]{max, min};
        }
        return new int[]{min, max};
    }

    private static boolean hasSalary(PreferenceEntity p) {
        return (p.getSalaryMinK() != null && p.getSalaryMinK() > 0) || (p.getSalaryMaxK() != null && p.getSalaryMaxK() > 0);
    }

    private static void salaryRange(StringBuilder out, PreferenceEntity p) {
        if (!hasSalary(p)) {
            return;
        }
        int[] r = range(p);
        String range;
        if (r[0] > 0 && r[1] > 0) {
            range = r[0] == r[1] ? r[0] + "K" : r[0] + "-" + r[1] + "K";
        } else if (r[0] > 0) {
            range = r[0] + "K 以上";
        } else {
            // 写「以下」等于告诉 HR 可以往下压，和谈薪规则矛盾。
            range = r[1] + "K 左右";
        }
        line(out, "期望月薪", range);
    }

    private static void list(StringBuilder out, String label, List<String> values) {
        if (values == null) {
            return;
        }
        String joined = values.stream().map(StringUtils::trimToEmpty).filter(StringUtils::isNotBlank)
                .map(v -> v.length() > 30 ? v.substring(0, 30) : v)
                .limit(30).collect(Collectors.joining("、"));
        if (StringUtils.isNotBlank(joined)) {
            line(out, label, joined);
        }
    }

    private static void line(StringBuilder out, String label, String value) {
        out.append("- ").append(label).append("：").append(value).append('\n');
    }
}
