package com.maple.ai.job.hunting.controller;

import com.maple.ai.job.hunting.emums.BizCodeEnum;
import com.maple.ai.job.hunting.frame.GlobalExceptionHandler;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.biz.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerIdentityHeadersTest {

    private RecordingUserService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new RecordingUserService();
        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    static Stream<Arguments> compatibleProofs() {
        return Stream.of(
                Arguments.of("synthetic-proof", null),
                Arguments.of(null, "synthetic-proof"),
                Arguments.of("synthetic-proof", "synthetic-proof"),
                Arguments.of("", "synthetic-proof"),
                Arguments.of(" \t", "synthetic-proof"),
                Arguments.of("synthetic-proof", " \t"));
    }

    static Stream<Arguments> conflictingProofs() {
        return Stream.of(
                Arguments.of("canonical-proof", "legacy-proof"),
                Arguments.of("synthetic-proof ", "synthetic-proof"));
    }

    @ParameterizedTest
    @MethodSource("compatibleProofs")
    void loginBindsCompatibleProofHeaders(String canonical, String legacy) throws Exception {
        mvc.perform(headers(post("/api/user/silently/login").param("uniqueId", "synthetic-uid"),
                        canonical, legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("synthetic-session"));

        assertEquals(1, service.calls);
        assertEquals("synthetic-uid", service.uid);
        assertEquals("synthetic-proof", service.proof);
    }

    @Test
    void multipartImportIgnoresClientUidAndBossProof() throws Exception {
        mvc.perform(headers(resumeRequest().param("uniqueId", "forged-client-uid"),
                        "synthetic-proof", "conflicting-untrusted-proof"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(1, service.calls);
        assertEquals(null, service.uid);
        assertEquals(null, service.proof);
        assertEquals("synthetic-resume", service.resumeId);
        assertEquals("SYNTHETIC RESUME", service.resumeText);
    }

    @Test
    void multipartImportDoesNotRequireBossIdentityHeaders() throws Exception {
        mvc.perform(resumeRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(1, service.calls);
        assertEquals(null, service.uid);
        assertEquals(null, service.proof);
    }

    @ParameterizedTest
    @MethodSource("conflictingProofs")
    void loginRejectsConflictingProofBeforeCallingService(String canonical, String legacy) throws Exception {
        mvc.perform(headers(post("/api/user/silently/login").param("uniqueId", "synthetic-uid"),
                        canonical, legacy))
                .andExpect(jsonPath("$.code").value(BizCodeEnum.IDENTITY_VERIFY_FAILED.getCode()));

        assertEquals(0, service.calls);
    }

    private MockHttpServletRequestBuilder resumeRequest() {
        return multipart("/api/user/import/resume")
                .file(new MockMultipartFile("file", "resume.txt", "text/plain",
                        "SYNTHETIC RESUME".getBytes(StandardCharsets.UTF_8)))
                .param("resumeId", "synthetic-resume");
    }

    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder request,
                                                  String canonical, String legacy) {
        if (canonical != null) {
            request.header("X-Boss-Token", canonical);
        }
        if (legacy != null) {
            request.header("Zp_token", legacy);
        }
        return request;
    }

    private static final class RecordingUserService extends UserService {
        private int calls;
        private String uid;
        private String proof;
        private String resumeId;
        private String resumeText;

        private void recordProof(String uniqueId, String bossToken) {
            calls++;
            uid = uniqueId;
            proof = bossToken;
        }

        @Override
        public String loginSilently(String uniqueId, String bossToken) {
            recordProof(uniqueId, bossToken);
            return "synthetic-session";
        }

        @Override
        public UserInfoVO importResume(InputStream stream, String resumeId) throws Exception {
            calls++;
            this.resumeId = resumeId;
            resumeText = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new UserInfoVO();
        }
    }
}
