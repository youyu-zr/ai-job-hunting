package com.maple.ai.job.hunting.frame.filter;

import cn.hutool.json.JSONUtil;
import com.google.common.cache.Cache;
import com.google.common.util.concurrent.RateLimiter;
import com.maple.ai.job.hunting.emums.BizCodeEnum;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void differentCanonicalProofsHaveDifferentIdentityQuotas() throws Exception {
        RequestRateLimitFilter filter = new RequestRateLimitFilter();
        keepGlobalQuotaAvailable(filter, "/api/user/silently/login");

        assertEquals(200, send(filter, request("/api/user/silently/login", "proof-a", null)).getStatus());
        assertEquals(200, send(filter, request("/api/user/silently/login", "proof-b", null)).getStatus());
    }

    @ParameterizedTest
    @CsvSource({
            "/api/user/silently/login,5",
            "/api/user/import/resume,10"
    })
    void canonicalAndLegacyNamesShareTheSameIdentityQuota(String uri, String retrySeconds) throws Exception {
        RequestRateLimitFilter filter = new RequestRateLimitFilter();
        keepGlobalQuotaAvailable(filter, uri);
        assertEquals(200, send(filter, request(uri, "same-proof", null)).getStatus());

        MockHttpServletResponse response = send(filter, request(uri, null, "same-proof"));

        assertRateRejection(response, retrySeconds);
    }

    @Test
    void blankCanonicalProofFallsBackToTheLegacyIdentityQuota() throws Exception {
        RequestRateLimitFilter filter = new RequestRateLimitFilter();
        String uri = "/api/user/silently/login";
        keepGlobalQuotaAvailable(filter, uri);
        assertEquals(200, send(filter, request(uri, null, "same-proof")).getStatus());

        assertRateRejection(send(filter, request(uri, " \t", "same-proof")), "5");
    }

    @Test
    void equalProofHeadersShareTheCanonicalOnlyIdentityQuota() throws Exception {
        RequestRateLimitFilter filter = new RequestRateLimitFilter();
        String uri = "/api/user/silently/login";
        keepGlobalQuotaAvailable(filter, uri);
        assertEquals(200, send(filter, request(uri, "same-proof", "same-proof")).getStatus());

        assertRateRejection(send(filter, request(uri, "same-proof", null)), "5");
    }

    @Test
    void conflictingProofIsRejectedEvenWhenGlobalQuotaIsExhausted() {
        RequestRateLimitFilter filter = new RequestRateLimitFilter();
        String uri = "/api/user/silently/login";
        exhaustGlobalQuota(filter, uri);
        AtomicBoolean chainCalled = new AtomicBoolean();

        ApplicationException failure = assertThrows(ApplicationException.class,
                () -> filter.doFilter(request(uri, "proof-a", "proof-b"),
                        new MockHttpServletResponse(), (req, res) -> chainCalled.set(true)));

        assertEquals(BizCodeEnum.IDENTITY_VERIFY_FAILED.getCode(), failure.getBizCode());
        assertFalse(chainCalled.get());
    }

    @ParameterizedTest
    @CsvSource({
            "/api/user/silently/login,5",
            "/api/user/import/resume,10",
            "/api/user/ai/config/test,10"
    })
    void repeatedIdentityRequestAdvertisesItsConfiguredInterval(String uri, String retrySeconds) throws Exception {
        RequestRateLimitFilter filter = new RequestRateLimitFilter();
        keepGlobalQuotaAvailable(filter, uri);
        assertEquals(200, send(filter, request(uri, "same-proof", "same-proof")).getStatus());

        assertRateRejection(send(filter, request(uri, "same-proof", "same-proof")), retrySeconds);
    }

    @ParameterizedTest
    @CsvSource({"/api/user/silently/login", "/api/user/import/resume"})
    void globalQuotaRejectionAdvertisesAtLeastOneSecond(String uri) throws Exception {
        RequestRateLimitFilter filter = new RequestRateLimitFilter();
        exhaustGlobalQuota(filter, uri);

        assertRateRejection(send(filter, request(uri, "synthetic-proof", null)), "1");
    }

    @ParameterizedTest
    @CsvSource({
            "/api/user/silently/login,5",
            "/api/user/import/resume,10",
            "/api/job/filter/one,1"
    })
    void concurrencyRejectionIncludesAlreadyConsumedIdentityInterval(String uri, String retrySeconds) throws Exception {
        RequestRateLimitFilter filter = new RequestRateLimitFilter();
        ReflectionTestUtils.setField(filter, "activeModelRequests", new Semaphore(0));

        assertRateRejection(send(filter, request(uri, "synthetic-proof", null)), retrySeconds);
    }

    @Test
    void downstreamFailureStillReleasesTheConcurrencySlot() {
        RequestRateLimitFilter filter = new RequestRateLimitFilter();
        Semaphore slots = new Semaphore(1);
        ReflectionTestUtils.setField(filter, "activeModelRequests", slots);

        assertThrows(ServletException.class,
                () -> filter.doFilter(request("/api/user/import/resume", "synthetic-proof", null),
                        new MockHttpServletResponse(), (req, res) -> {
                            throw new ServletException("synthetic downstream failure");
                        }));

        assertEquals(1, slots.availablePermits());
    }

    private MockHttpServletRequest request(String uri, String canonical, String legacy) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr("192.0.2.1");
        request.addParameter("uniqueId", "synthetic-uid");
        if (canonical != null) {
            request.addHeader("X-Boss-Token", canonical);
        }
        if (legacy != null) {
            request.addHeader("Zp_token", legacy);
        }
        return request;
    }

    private MockHttpServletResponse send(RequestRateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));
        assertEquals(response.getStatus() != 429, chainCalled.get());
        return response;
    }

    private void assertRateRejection(MockHttpServletResponse response, String retrySeconds) throws Exception {
        assertEquals(429, response.getStatus());
        assertEquals(retrySeconds, response.getHeader("Retry-After"));
        assertEquals(429, JSONUtil.parseObj(response.getContentAsString()).getInt("code"));
    }

    private void keepGlobalQuotaAvailable(RequestRateLimitFilter filter, String uri) {
        limiterCache(filter).put("route|" + uri, RateLimiter.create(1_000_000));
    }

    private void exhaustGlobalQuota(RequestRateLimitFilter filter, String uri) {
        RateLimiter limiter = RateLimiter.create(0.000001);
        assertTrue(limiter.tryAcquire());
        limiterCache(filter).put("route|" + uri, limiter);
    }

    @SuppressWarnings("unchecked")
    private Cache<String, RateLimiter> limiterCache(RequestRateLimitFilter filter) {
        return (Cache<String, RateLimiter>) ReflectionTestUtils.getField(filter, "limiters");
    }
}
