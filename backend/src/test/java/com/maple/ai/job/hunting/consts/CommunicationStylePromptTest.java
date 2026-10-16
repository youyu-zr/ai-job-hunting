package com.maple.ai.job.hunting.consts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunicationStylePromptTest {

    @Test
    void defaultsToNaturalStyle() {
        assertEquals("NATURAL", CommunicationStylePrompt.normalize(null));
        assertTrue(CommunicationStylePrompt.resolve(null).length() > 0);
    }

    @Test
    void keepsOnlyTwoSelectableStyles() {
        assertEquals("NATURAL", CommunicationStylePrompt.normalize("CAUTIOUS"));
        assertEquals("NATURAL", CommunicationStylePrompt.normalize("NATURAL"));
        assertEquals("PROACTIVE", CommunicationStylePrompt.normalize("PROACTIVE"));
        assertNotEquals(
                CommunicationStylePrompt.resolve("NATURAL"),
                CommunicationStylePrompt.resolve("PROACTIVE")
        );
    }

    @Test
    void unknownStyleFallsBackToNatural() {
        assertEquals(
                CommunicationStylePrompt.resolve("NATURAL"),
                CommunicationStylePrompt.resolve("old-style")
        );
    }
}
