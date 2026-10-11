package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.frame.filter.CorsFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

        assertEquals("Content-Type, Authorization, Zp_token", response.getHeader("Access-Control-Allow-Headers"));
    }
}