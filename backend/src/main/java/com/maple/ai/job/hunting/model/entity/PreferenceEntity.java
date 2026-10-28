package com.maple.ai.job.hunting.model.entity;

import lombok.Data;

import java.util.List;

/**
 * 偏好设置实体类。
 *
 * @author maple
 * @since 2024/09/02
 */

@Data
public class PreferenceEntity {

    /**
     * 公司名包含开关
     */
    private boolean cniE;

    /**
     * 公司名包含
     */
    private List<String> cni;

    /**
     * 公司名排除开关
     */
    private boolean cneE;

    /**
     * 公司名排除
     */
    private List<String> cne;

    /**
     * 工作名包含开关
     */
    private boolean jniE;

    /**
     * 工作名包含
     */
    private List<String> jni;

    /**
     * 工作名排除开关
     */
    private boolean jneE;

    /**
     * 工作名排除
     */
    private List<String> jne;

    /**
     * 工作名内容排除开关
     */
    private boolean jceE;

    /**
     * 工作名内容排除
     */
    private List<String> jce;

    /**
     * 工作内容包含开关
     */
    private boolean jciE;

    /**
     * 工作内容包含
     */
    private List<String> jci;

    /**
     * 薪资范围开关
     */
    private boolean srE;

    /**
     * 薪资范围类型
     */
    private int srT;

    /**
     * 薪资范围
     */
    private String sr;

    /**
     * 公司规模范围开关
     */
    private boolean csrE;

    /**
     * 公司规模范围
     */
    private String csr;

    /**
     * 过滤猎头开关
     */
    private boolean fhE;

    /**
     * 仅投递在线 BOSS（前端开关；以前实体里没有，保存时会被丢掉）
     */
    private boolean polE;

    /**
     * 投递间隔下限 / 上限（秒，前端用）
     */
    private Integer pi;

    private Integer npi;

    /**
     * 发送自定义招呼语
     */
    private boolean cgE;

    /**
     * 自定义招呼语
     */
    private String cg;

    /** Whether to send the uploaded image resume after a successful delivery. */
    private boolean cIE;

    /** BOSS image-resume URLs stored as originImage===tinyImage. */
    private String cI;

    /**
     * 投递前 AI 语义过滤开关。
     */
    private boolean afE;

    /**
     * 投递前 AI 语义过滤条件。
     */
    private String af;

    /**
     * 预设问题开关
     */
    private boolean ppE;

    /**
     * 预设问题
     */
    private String pp;

    /** Enables one natural response to a soft objection; explicit rejection still closes deterministically. */
    private boolean rfE;

    /** Optional soft-objection retention guidance, not a refusal close message. */
    private String rf;

    /** User-specific job requirements used for matching and relevant HR conversations. */
    private String jobGoal;

    private String unacceptableConditions;

    private String clarifyConditions;

    private String communicationPreferences;

    /** CAUTIOUS, NATURAL, or PROACTIVE. */
    private String communicationStyle;

    /**
     * 每轮邮件开启
     */
    private boolean ermE;

    /**
     * 高意向ai坐席停止开关
     */
    private boolean hiaE;

    /** 普通营销推广消息是否允许自动回复，默认关闭。 */
    private boolean marketingReplyE;

    /** AI 回复里是否允许带出手机号/微信号/邮箱，默认关闭：命中就不发，交给本人确认。 */
    private boolean contactShareE;

    /** Editable guidance for promotion types the user does not want answered. */
    private String promotionReplyPolicy;

    /**
     * 对话聊天轮数 Chat rounds （高意向邮件开关） 开关
     */
    private boolean crE;

    /**
     * 对话聊天轮数 count
     */
    private Integer crC;

    /**
     * 对话聊天轮数 Key 关键字
     */
    private List<String> crK;

    /**
     * 默认风险包版本。
     * <p>
     * 该字段用于区分“系统尚未初始化默认风险包”和“用户已经看过并自行修改过风险包”。
     * 一旦写入，后续不会因为用户删除某些排除词而自动长回来。
     */
    private String drpV;

    /** One-time initialization marker for editable personal preference defaults. */
    private String pdpV;

    /** Server-owned monotonic revision for account-level AI-seat changes. */
    private Long aiSeatRevision;

    /** Personal resume/current city used only for consistency hints. */
    private boolean locationGuardE;

    private String resumeCity;

    private List<String> targetCities;

    /** Number of consecutive location mismatches before pausing delivery. */
    private Integer locationMismatchThreshold;

    // ---- 偏好页 v3 结构化字段（2026-10-29）。null = 用户没填，按默认处理。----

    /** 住在哪：城市 + 区/街道/地铁站，交给 AI 估算通勤。 */
    private String homeCity;

    private String homeArea;

    /** 单程通勤上限（分钟），null/0 = 不限。 */
    private Integer commuteMaxMinutes;

    /** 是否接受远程 / 外地岗位。 */
    private Boolean remoteOk;

    private Boolean relocateOk;

    /** 期望月薪区间与底线（单位 K），底线不对 HR 说出。 */
    private Integer salaryMinK;

    private Integer salaryMaxK;

    private Integer salaryFloorK;

    /** 想做 / 坚决不做的方向标签。 */
    private List<String> wantTags;

    private List<String> avoidTags;

    /** 其他底线，例如「大小周」「单休」「夜班」「常出差」「试用期超6个月」「不交社保」「入职交钱」。 */
    private List<String> dealbreakers;

    /** 不投、不回的公司（现公司、前公司及关联公司）。 */
    private List<String> blockedCompanies;

    /** 最快到岗时间：随时 / 一周内 / 两周内 / 一个月内。 */
    private String arrivalTime;

    /**
     * 「停下来叫我」各事件的处理方式：PAUSE（停下叫我）/ DECLINE（AI 先婉拒或问清）/ ALLOW（让 AI 处理）。
     * null 时按 {@link com.maple.ai.job.hunting.utils.StopEventPolicy} 里的默认值。
     */
    private String stopInterview;

    private String stopOffer;

    private String stopSalary;

    private String stopContact;

    private String stopResume;

    private String stopPersonal;

    /** 停下时先替你回一句不做承诺的过渡话（每个会话一次）。null = 开。 */
    private Boolean holdReplyE;
}
