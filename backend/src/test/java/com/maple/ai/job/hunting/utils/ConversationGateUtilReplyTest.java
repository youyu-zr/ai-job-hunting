package com.maple.ai.job.hunting.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationGateUtilReplyTest {

    @Test
    void rejectionAnswerCanBeRecognizedSoItWillNotBeTreatedAsHighInterestEmail() {
        assertTrue(ConversationGateUtil.isRejectionAnswer("这类岗位我暂时不考虑"));
        assertTrue(ConversationGateUtil.isRejectionAnswer("和我的求职方向不太匹配"));
        assertFalse(ConversationGateUtil.isRejectionAnswer("我这边需要确认一下时间"));
    }
}
