package com.maple.ai.job.hunting.model.vo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class MessageTriageResultVOTest {

    @Test
    void exposesTheDeclaredCategoriesAndActions() {
        assertIterableEquals(List.of(
                MessageTriageResultVO.Category.NORMAL_JOB,
                MessageTriageResultVO.Category.PROMOTION,
                MessageTriageResultVO.Category.HIGH_RISK,
                MessageTriageResultVO.Category.PLATFORM_EVENT,
                MessageTriageResultVO.Category.UNCERTAIN,
                MessageTriageResultVO.Category.PROCESSING_ERROR
        ), List.of(MessageTriageResultVO.Category.values()));
        assertIterableEquals(List.of(
                MessageTriageResultVO.Action.REPLY,
                MessageTriageResultVO.Action.SILENT_CURRENT_MESSAGE,
                MessageTriageResultVO.Action.BLOCK_CURRENT_JOB,
                MessageTriageResultVO.Action.NEEDS_USER_CONFIRMATION,
                MessageTriageResultVO.Action.NO_ACTION_RETRY
        ), List.of(MessageTriageResultVO.Action.values()));
    }

    @Test
    void storesTheMessageTriageDecisionContract() {
        MessageTriageResultVO result = MessageTriageResultVO.builder()
                .category(MessageTriageResultVO.Category.UNCERTAIN)
                .action(MessageTriageResultVO.Action.NEEDS_USER_CONFIRMATION)
                .reasonCode("MISSING_WORK_MODE")
                .plainLanguageReason("工作方式还没有说明")
                .clarificationQuestions(List.of("是否支持远程协作？"))
                .evidence(List.of("岗位描述未写明工作方式"))
                .build();

        assertEquals(MessageTriageResultVO.Category.UNCERTAIN, result.getCategory());
        assertEquals(MessageTriageResultVO.Action.NEEDS_USER_CONFIRMATION, result.getAction());
        assertEquals("MISSING_WORK_MODE", result.getReasonCode());
        assertEquals("工作方式还没有说明", result.getPlainLanguageReason());
        assertIterableEquals(List.of("是否支持远程协作？"), result.getClarificationQuestions());
        assertIterableEquals(List.of("岗位描述未写明工作方式"), result.getEvidence());
    }
}
