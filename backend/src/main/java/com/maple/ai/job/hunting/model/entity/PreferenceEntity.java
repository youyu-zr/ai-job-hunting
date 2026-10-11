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

    /** Personal resume/current city used only for consistency hints. */
    private boolean locationGuardE;

    private String resumeCity;

    private List<String> targetCities;

    /** Number of consecutive location mismatches before pausing delivery. */
    private Integer locationMismatchThreshold;
}
