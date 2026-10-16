package com.maple.ai.job.hunting.model.param;

import com.alibaba.fastjson.JSONObject;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author maple
 * Created Date: 2024/5/14 21:28
 * Description:
 */

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobSeekerClonedParam {

    @Size(max = 5000, message = "问题超过字符长度限制")
    @NotEmpty(message = "question不能为空")
    private String question;

    /**
     * encryptJobId:bossUid
     */
    @NotEmpty(message = "jobKey不能为空")
    private String jobKey;

    /**
     * 职位信息
     */
    private JSONObject jobInfo;

    /** Known BOSS message body type supplied by the browser; never inferred from text. */
    private Integer platformBodyType;

    public void setPlatformBodyType(Integer platformBodyType) {
        this.platformBodyType = platformBodyType != null
                && java.util.Set.of(1, 4, 8, 12, 14).contains(platformBodyType)
                ? platformBodyType : null;
    }

    public JobSeekerClonedParam(String question, String jobKey, JSONObject jobInfo) {
        this.question = question;
        this.jobKey = jobKey;
        this.jobInfo = jobInfo;
    }
}
