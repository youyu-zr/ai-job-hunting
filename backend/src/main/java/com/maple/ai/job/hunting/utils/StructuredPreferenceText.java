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
 *   并写明「只说期望区间、不答应任何数字」。</li>
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

    public static String forReply(PreferenceEntity p) {
        if (p == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        // 规则行放在数字前面：分诊偏好过长被截断时先丢后面的内容，不能只剩区间、丢了约束。
        if (hasSalary(p)) {
            line(out, "谈薪规则", "HR 问期望薪资时只说下面的区间；不说底线；HR 报出具体数字时不答应也不拒绝，可以问薪资构成");
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

    private static boolean hasSalary(PreferenceEntity p) {
        return (p.getSalaryMinK() != null && p.getSalaryMinK() > 0) || (p.getSalaryMaxK() != null && p.getSalaryMaxK() > 0);
    }

    private static void salaryRange(StringBuilder out, PreferenceEntity p) {
        if (!hasSalary(p)) {
            return;
        }
        Integer min = p.getSalaryMinK();
        Integer max = p.getSalaryMaxK();
        String range;
        if (min != null && min > 0 && max != null && max > 0) {
            range = min + "-" + max + "K";
        } else if (min != null && min > 0) {
            range = min + "K 以上";
        } else {
            range = max + "K 以下";
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
