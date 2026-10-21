package com.maple.ai.job.hunting.frame.filter;

import com.maple.ai.job.hunting.emums.BizCodeEnum;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginFilterResumeImportTest {

    @Test
    void resumeImportRequiresApplicationSessionEvenIfOldConfigMarksItPublic() {
        LoginFilter filter = new LoginFilter();
        ReflectionTestUtils.setField(filter, "freeLoginList",
                List.of("/api/user/silently/login", "/api/user/import/resume"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/import/resume");

        ApplicationException failure = assertThrows(ApplicationException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    throw new AssertionError("unauthenticated import must not reach the controller");
                }));

        assertEquals(BizCodeEnum.NOT_LOGIN.getCode(), failure.getBizCode());
    }

    @Test
    void resumeImportRequiresApplicationSessionEvenIfOldConfigUsesWildcard() {
        LoginFilter filter = new LoginFilter();
        ReflectionTestUtils.setField(filter, "freeLoginList", List.of("/api/user/**"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/import/resume");

        ApplicationException failure = assertThrows(ApplicationException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    throw new AssertionError("wildcard public config must not expose resume import");
                }));

        assertEquals(BizCodeEnum.NOT_LOGIN.getCode(), failure.getBizCode());
    }
}
