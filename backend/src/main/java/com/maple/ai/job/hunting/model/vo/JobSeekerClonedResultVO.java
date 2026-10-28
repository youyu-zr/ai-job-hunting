package com.maple.ai.job.hunting.model.vo;

import com.maple.ai.job.hunting.emums.BossOperationTypeEnum;
import com.maple.ai.job.hunting.emums.JobSeekerClonedAnswerTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * @author maple
 * Created Date: 2024/5/14 14:41
 * Description:
 */

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobSeekerClonedResultVO {

    /**
     * 回复类型
     * @see com.maple.ai.job.hunting.emums.JobSeekerClonedAnswerTypeEnum
     */
    private List<Integer> answerTypeList;

    /**
     * 回复内容
     */
    private String answerContent;

    /** Stable machine-readable reason when answerTypeList contains STOP. */
    private String stopReason;

    /**
     * 「停下来叫我」命中的事件（StopEventPolicy.Event 名）。有值时前端要提醒用户：
     * 可能同时带一句过渡话（MSG_TEXT），也可能是纯 STOP。
     */
    private String handoffEvent;

    /** 给用户看的事件说明。 */
    private String handoffLabel;

    /** 高意向提醒：只提醒，不停 AI。 */
    private boolean highInterestNotice;

    /** Whether this result represents a retryable processing failure. */
    private boolean retryable;

    /**
     * 操作类型
     * @see BossOperationTypeEnum
     */
    private List<Integer> operationTypeList;

    public JobSeekerClonedResultVO(List<Integer> answerTypeList, String answerContent) {
        this.answerTypeList = answerTypeList;
        this.answerContent = answerContent;
    }

    public JobSeekerClonedResultVO(List<Integer> answerTypeList, String answerContent, String stopReason) {
        this.answerTypeList = answerTypeList;
        this.answerContent = answerContent;
        this.stopReason = stopReason;
    }

    public JobSeekerClonedResultVO(List<Integer> answerTypeList, String answerContent, List<Integer> operationTypeList) {
        this.answerTypeList = answerTypeList;
        this.answerContent = answerContent;
        this.operationTypeList = operationTypeList;
    }

    public JobSeekerClonedResultVO(JobSeekerClonedAnswerTypeEnum answerType, String answerContent) {
        this.answerTypeList = Collections.singletonList(answerType.getCode());
        this.answerContent = answerContent;
    }

    public JobSeekerClonedResultVO(JobSeekerClonedAnswerTypeEnum answerType, String answerContent, String stopReason) {
        this.answerTypeList = Collections.singletonList(answerType.getCode());
        this.answerContent = answerContent;
        this.stopReason = stopReason;
    }
}
