package com.maple.ai.job.hunting.consts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIPromptBoundaryTest {

    @Test
    void communicationPromptDoesNotImposeFixedLengthOrKeywordTriggers() {
        String prompt = AIPromptStrConstant.AI_SEAT_SYSTEM_PROMPT;

        assertFalse(prompt.contains("50字以内"));
        assertFalse(prompt.contains("15字以内"));
        assertFalse(prompt.contains("触发词"));
        assertFalse(prompt.contains("不合适"));
        assertTrue(prompt.contains("根据完整上下文"));
    }
}
