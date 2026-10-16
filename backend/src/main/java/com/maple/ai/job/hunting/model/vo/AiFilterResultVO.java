package com.maple.ai.job.hunting.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI 滤镜结果 vo
 *
 * @author gaoping
 * @since 2025/06/14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiFilterResultVO {

    /**
     * 是否过滤
     */
    private Boolean filter;

    /**
     * 过滤原因
     */
    private String reason;

    /**
     * 岗位本身质量评分：1-5，越高越值得投递。
     */
    private Integer jobScore;

    /**
     * 公司质量评分：1-5，越高越稳定可信。
     */
    private Integer companyScore;

    /**
     * 候选人与岗位匹配评分：1-5，越高越匹配。
     */
    private Integer matchScore;

    /**
     * 风险等级：LOW / MEDIUM / HIGH。
     */
    private String riskLevel;

    /**
     * 建议动作：DELIVER / FILTER；技术异常通过服务/API 错误路径处理。
     */
    private String recommendedAction;

    /**
     * 需要向 HR 确认的问题。
     */
    private List<String> clarificationQuestions;

    /** REMOTE / ONSITE / HYBRID / UNKNOWN. */
    private String workMode;

    /** Direct evidence from the job text supporting workMode. */
    private List<String> workModeEvidence;

    /**
     * 正向信号。
     */
    private List<String> goodSignals;

    /**
     * 风险信号。
     */
    private List<String> riskSignals;

    /**
     * 如后续进入面试，建议提前补的技能点。
     */
    private List<String> prepSkills;
}
