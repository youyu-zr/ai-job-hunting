package com.maple.ai.job.hunting.frame.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalFilterCanonicalPathTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/job/seeker/cloned/ask;x=1",
            "/api/job/seeker/cloned/%61sk",
            "/api//job/filter/one",
            "/api/job/./filter/one",
            "/api/x/../job/filter/one",
            "/api\\job"
    })
    void variantPathsThatWouldSkipExactUriFiltersAreRejected(String uri) throws Exception {
        assertTrue(GlobalFilter.isNonCanonicalPath(uri));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        new GlobalFilter().doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertEquals(400, response.getStatus());
        assertFalse(chainCalled.get());
    }

    @Test
    void normalPathsPassThrough() throws Exception {
        assertFalse(GlobalFilter.isNonCanonicalPath("/api/job/seeker/cloned/ask"));
        assertFalse(GlobalFilter.isNonCanonicalPath("/api/user/userinfo"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/userinfo");
        request.setQueryString("a=%20b");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        new GlobalFilter().doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertTrue(chainCalled.get());
    }
}
