package com.maple.ai.job.hunting.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageTriageResultVO {

    private Category category;

    private Action action;

    private String reasonCode;

    private String plainLanguageReason;

    private List<String> clarificationQuestions;

    private List<String> evidence;

    public enum Category {
        NORMAL_JOB,
        PROMOTION,
        HIGH_RISK,
        PLATFORM_EVENT,
        UNCERTAIN,
        PROCESSING_ERROR
    }

    public enum Action {
        REPLY,
        SILENT_CURRENT_MESSAGE,
        BLOCK_CURRENT_JOB,
        NEEDS_USER_CONFIRMATION,
        NO_ACTION_RETRY
    }
}
