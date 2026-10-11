package com.maple.ai.job.hunting.service.ai;

import com.maple.ai.job.hunting.consts.CommunicationStylePrompt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractAIServicePromptBoundaryTest {

    @Test
    void builtInCommunicationStylesRemainAvailableAfterFreePromptRemoval() {
        assertTrue(CommunicationStylePrompt.resolve("NATURAL").contains("自然稳妥"));
        assertTrue(CommunicationStylePrompt.resolve("PROACTIVE").contains("主动了解"));
    }
}
