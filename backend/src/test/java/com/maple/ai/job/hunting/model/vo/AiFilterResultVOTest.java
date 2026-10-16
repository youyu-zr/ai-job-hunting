package com.maple.ai.job.hunting.model.vo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiFilterResultVOTest {

    @Test
    void carriesOptionalClarificationQuestionsAlongsideExistingFields() {
        AiFilterResultVO result = AiFilterResultVO.builder()
                .filter(false)
                .reason("岗位信息不完整但没有明确风险")
                .jobScore(4)
                .companyScore(3)
                .matchScore(4)
                .riskLevel("LOW")
                .recommendedAction("DELIVER")
                .clarificationQuestions(List.of("工作地点在哪里？"))
                .build();

        assertEquals(false, result.getFilter());
        assertEquals("岗位信息不完整但没有明确风险", result.getReason());
        assertEquals(4, result.getJobScore());
        assertEquals(3, result.getCompanyScore());
        assertEquals(4, result.getMatchScore());
        assertEquals("LOW", result.getRiskLevel());
        assertEquals("DELIVER", result.getRecommendedAction());
        assertIterableEquals(List.of("工作地点在哪里？"), result.getClarificationQuestions());
    }

    @Test
    void allowsClarificationQuestionsToBeOmitted() {
        AiFilterResultVO result = AiFilterResultVO.builder()
                .filter(false)
                .recommendedAction("DELIVER")
                .build();

        assertNull(result.getClarificationQuestions());
    }
}
