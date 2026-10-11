package com.maple.ai.job.hunting.model.param;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * aiFilter 参数
 *
 * @author gaoping
 * @since 2025/06/14
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AIFilterParam {

    @NotEmpty(message = "ai过滤提示词不能为空")
    private String prompt;

    private String jobBaseInfo;

    private String jobExtInfo;

    /** Candidate facts used only to calculate the job match score. */
    private String resumeContent;

    /** Serialized candidate preferences used only for this screening decision. */
    private String preference;

    public AIFilterParam(String prompt, String jobBaseInfo, String jobExtInfo) {
        this.prompt = prompt;
        this.jobBaseInfo = jobBaseInfo;
        this.jobExtInfo = jobExtInfo;
    }
}
