package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.frame.filter.CorsFilter;
import com.maple.ai.job.hunting.frame.filter.RequestRateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the CORS credential policy.
 *
 * <p>The regression this covers: the filter used to echo the raw {@code Origin} header while also
 * sending {@code Access-Control-Allow-Credentials: true}, which lets any website read authenticated
 * responses for a logged-in user.
 */
class CorsFilterOriginTest {

    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";

    private MockHttpServletResponse run(String origin) throws Exception {
        CorsFilter filter = new CorsFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/current");
        MockHttpServletResponse response = new MockHttpServletResponse();
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        FilterChain chain = (ServletRequest req, ServletResponse res) -> {
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void evilOriginIsNotEchoedAndGetsNoCredentialGrant() throws Exception {
        MockHttpServletResponse response = run("https://evil.example");

        assertNull(response.getHeader(ALLOW_ORIGIN));
        assertNull(response.getHeader(ALLOW_CREDENTIALS));
    }

    @Test
    void productionOriginIsAllowedWithCredentials() throws Exception {
        MockHttpServletResponse response = run("https://aijob.zxpnb.top");

        assertEquals("https://aijob.zxpnb.top", response.getHeader(ALLOW_ORIGIN));
        assertEquals("true", response.getHeader(ALLOW_CREDENTIALS));
        assertEquals("Origin", response.getHeader("Vary"));
    }

    @Test
    void bossOriginIsAllowedForTokenedLoginPreflight() throws Exception {
        CorsFilter filter = new CorsFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/user/silently/login");
        request.addHeader("Origin", "https://www.zhipin.com");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "content-type,x-boss-token,zp_token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (ServletRequest req, ServletResponse res) -> {
            chainCalled.set(true);
        });

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals("https://www.zhipin.com", response.getHeader(ALLOW_ORIGIN));
        assertEquals("true", response.getHeader(ALLOW_CREDENTIALS));
        assertEquals("Content-Type, Authorization, X-Boss-Token, Zp_token",
                response.getHeader("Access-Control-Allow-Headers"));
        assertEquals("POST, GET, OPTIONS, DELETE, PUT", response.getHeader("Access-Control-Allow-Methods"));
        assertEquals("Origin, Access-Control-Request-Method, Access-Control-Request-Headers",
                response.getHeader("Vary"));
        assertFalse(chainCalled.get());
    }

    @Test
    void ordinaryOptionsRequestStillReachesTheApplication() throws Exception {
        CorsFilter filter = new CorsFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/user/silently/login");
        request.addHeader("Origin", "https://www.zhipin.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (ServletRequest req, ServletResponse res) -> {
            chainCalled.set(true);
        });

        assertTrue(chainCalled.get());
    }

    @Test
    void bossOriginSpoofIsNotAllowed() throws Exception {
        assertNull(run("https://www.zhipin.com.evil.example").getHeader(ALLOW_ORIGIN));
    }

    @Test
    void localDevOriginsAreAllowed() throws Exception {
        assertEquals("http://localhost:5173", run("http://localhost:5173").getHeader(ALLOW_ORIGIN));
        assertEquals("http://127.0.0.1:4173", run("http://127.0.0.1:4173").getHeader(ALLOW_ORIGIN));
    }

    @Test
    void requestWithoutOriginGetsNoCorsHeaders() throws Exception {
        MockHttpServletResponse response = run(null);

        assertNull(response.getHeader(ALLOW_ORIGIN));
        assertNull(response.getHeader(ALLOW_CREDENTIALS));
    }

    @Test
    void nearMissAndSuffixSpoofOriginsAreRejected() throws Exception {
        assertNull(run("https://aijob.zxpnb.top.evil.example").getHeader(ALLOW_ORIGIN));
        assertNull(run("https://evilaijob.zxpnb.top").getHeader(ALLOW_ORIGIN));
        assertNull(run("http://aijob.zxpnb.top").getHeader(ALLOW_ORIGIN));
        assertNull(run("null").getHeader(ALLOW_ORIGIN));
    }

    @Test
    void extraAllowedOriginsAreHonouredWithoutCodeChange() throws Exception {
        CorsFilter filter = new CorsFilter();
        ReflectionTestUtils.setField(filter, "extraAllowedOrigins", " https://staging.example , https://second.example ");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/current");
        request.addHeader("Origin", "https://staging.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ServletRequest req, ServletResponse res) -> {
        });

        assertEquals("https://staging.example", response.getHeader(ALLOW_ORIGIN));
    }

    @Test
    void zpTokenHeaderStaysAllowedForPreflight() throws Exception {
        MockHttpServletResponse response = run("https://aijob.zxpnb.top");

        assertEquals("Content-Type, Authorization, X-Boss-Token, Zp_token",
                response.getHeader("Access-Control-Allow-Headers"));
    }

    @Test
    void crossOriginRateRejectionExposesRetryAfterAndAuthorization() throws Exception {
        CorsFilter cors = new CorsFilter();
        RequestRateLimitFilter rate = new RequestRateLimitFilter();
        ReflectionTestUtils.setField(rate, "activeModelRequests", new Semaphore(0));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/import/resume");
        request.addHeader("Origin", "https://www.zhipin.com");
        request.addHeader("X-Boss-Token", "synthetic-proof");
        request.addParameter("uniqueId", "synthetic-uid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        cors.doFilter(request, response,
                (req, res) -> rate.doFilter(req, res, (nextReq, nextRes) -> chainCalled.set(true)));

        assertEquals(429, response.getStatus());
        assertEquals("https://www.zhipin.com", response.getHeader(ALLOW_ORIGIN));
        assertEquals("Authorization, Retry-After", response.getHeader("Access-Control-Expose-Headers"));
        assertEquals("10", response.getHeader("Retry-After"));
        assertFalse(chainCalled.get());
    }
}
