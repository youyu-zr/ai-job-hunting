package com.maple.ai.job.hunting.frame.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRateLimitFilterTest {

    @Test
    void expensiveRoutesHavePerIdentityRateLimits() {
        assertEquals(0.2, RequestRateLimitFilter.permitsPerSecond("/api/user/silently/login"));
        assertEquals(0.1, RequestRateLimitFilter.permitsPerSecond("/api/user/import/resume"));
        assertEquals(0.1, RequestRateLimitFilter.permitsPerSecond("/api/user/ai/config/test"));
        assertEquals(2.0, RequestRateLimitFilter.permitsPerSecond("/api/job/filter/one"));
        assertEquals(2.0, RequestRateLimitFilter.permitsPerSecond("/api/job/ai/assistant/generate/greeting"));
        assertEquals(3.0, RequestRateLimitFilter.permitsPerSecond("/api/job/seeker/cloned/ask"));
        assertEquals(0, RequestRateLimitFilter.permitsPerSecond("/api/user/userinfo"));
        assertEquals(10.0, RequestRateLimitFilter.globalPermitsPerSecond("/api/user/silently/login"));
        assertEquals(2.0, RequestRateLimitFilter.globalPermitsPerSecond("/api/user/import/resume"));
    }

    @Test
    void rateLimiterRejectsImmediateRepeatedRequests() {
        RequestRateLimitFilter filter = new RequestRateLimitFilter();

        assertTrue(filter.tryAcquire("test-identity", 0.1));
        assertFalse(filter.tryAcquire("test-identity", 0.1));
    }
}
