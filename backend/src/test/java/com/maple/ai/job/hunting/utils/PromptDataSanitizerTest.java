package com.maple.ai.job.hunting.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptDataSanitizerTest {

    @Test
    void removesLegacyLengthDirectiveWithoutDroppingTheFollowingPreference() {
        String cleaned = PromptDataSanitizer.removeLegacyReplyLengthDirectives(
                "普通交流回复1-3句，优先了解团队和技术栈"
        );

        assertEquals("优先了解团队和技术栈", cleaned);
    }

    @Test
    void removesUnboundedLegacyLengthStatementWithoutDroppingTheFollowingPreference() {
        String cleaned = PromptDataSanitizer.removeLegacyReplyLengthDirectives(
                "没有固定句数、固定字数和固定追问轮数。主动了解岗位细节"
        );

        assertEquals("主动了解岗位细节", cleaned);
    }
}
