package com.maple.ai.job.hunting.service.biz;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the identity-forgery guard protecting /api/user/silently/login and /api/user/import/resume.
 *
 * <p>The network call is stubbed by overriding the single fetch hook, so the real decision table in
 * the production verifier is what gets exercised.
 */
class BossIdentityVerifierTest {

    @Test
    void missingUidOrTokenIsRejected() {
        StubVerifier verifier = new StubVerifier("{\"code\":0,\"zpData\":{\"uid\":\"100000001\"}}");

        assertFalse(verifier.verify(null, "token"));
        assertFalse(verifier.verify("100000001", null));
        assertFalse(verifier.verify("100000001", "   "));
        assertFalse(verifier.verify("  ", "token"));
        assertThrows(ApplicationException.class, () -> verifier.requireOwnership("100000001", null));
    }

    @Test
    void matchingUidIsAccepted() {
        StubVerifier verifier = new StubVerifier("{\"code\":0,\"zpData\":{\"uid\":\"100000001\"}}");

        assertTrue(verifier.verify("100000001", "valid-token"));
        assertDoesNotThrow(() -> verifier.requireOwnership("100000001", "valid-token"));
    }

    @Test
    void uidIsTrimmedBeforeComparison() {
        StubVerifier verifier = new StubVerifier("{\"code\":0,\"zpData\":{\"uid\":100000001}}");

        assertTrue(verifier.verify(" 100000001 ", "valid-token"));
    }

    @Test
    void differentUidIsRejected() {
        StubVerifier verifier = new StubVerifier("{\"code\":0,\"zpData\":{\"uid\":\"100000002\"}}");

        assertFalse(verifier.verify("100000001", "victim-token"));
        assertThrows(ApplicationException.class, () -> verifier.requireOwnership("100000001", "victim-token"));
    }

    @Test
    void expiredOrInvalidTokenIsRejected() {
        StubVerifier verifier = new StubVerifier("{\"code\":7,\"message\":\"当前登录状态已失效\",\"zpData\":{}}");

        assertFalse(verifier.verify("100000001", "stale-token"));
    }

    @Test
    void alternateUidFieldNamesAreAccepted() {
        assertTrue(new StubVerifier("{\"code\":0,\"zpData\":{\"userId\":\"100000001\"}}")
                .verify("100000001", "valid-token"));
        assertTrue(new StubVerifier("{\"code\":0,\"zpData\":{\"geekId\":\"100000001\"}}")
                .verify("100000001", "valid-token"));
    }

    @Test
    void unreachableBossCannotProveAccountOwnership() {
        StubVerifier verifier = new StubVerifier(null);

        assertFalse(verifier.verify("100000001", "valid-token"));
        assertThrows(ApplicationException.class, () -> verifier.requireOwnership("100000001", "valid-token"));
    }

    @Test
    void unrecognisedPayloadCannotProveAccountOwnership() {
        assertFalse(new StubVerifier("{\"code\":10,\"message\":\"未知的请求方法\",\"zpData\":{}}")
                .verify("100000001", "valid-token"));
        assertFalse(new StubVerifier("{\"code\":0,\"zpData\":{}}")
                .verify("100000001", "valid-token"));
    }

    @Test
    void networkFailureCannotCreateAnAccountSession() {
        BossIdentityVerifier verifier = new BossIdentityVerifier() {
            @Override
            JSONObject fetchUserInfo(String bossToken) {
                throw new IllegalStateException("synthetic network failure");
            }
        };
        assertFalse(verifier.verify("100000001", "unverified-token"));
        assertThrows(ApplicationException.class,
                () -> verifier.requireOwnership("100000001", "unverified-token"));
    }

    @Test
    void disabledVerificationFailsClosed() {
        StubVerifier verifier = new StubVerifier("{\"code\":0,\"zpData\":{\"uid\":\"100000002\"}}");
        assertFalse(verifier.verify("100000001", "victim-token"));

        ReflectionTestUtils.setField(verifier, "enabled", false);

        assertFalse(verifier.verify("100000001", "victim-token"));
        assertThrows(ApplicationException.class,
                () -> verifier.requireOwnership("100000001", "victim-token"));
    }

    @Test
    void extractUidIgnoresBlankValues() {
        JSONObject zpData = JSONUtil.parseObj("{\"uid\":\"\",\"userId\":\"100000001\"}");

        assertEquals("100000001", ReflectionTestUtils.invokeMethod(new StubVerifier("{}"), "extractUid", zpData));
        assertNull(ReflectionTestUtils.invokeMethod(new StubVerifier("{}"), "extractUid", JSONUtil.parseObj("{\"uid\":\"\"}")));
    }

    /**
     * Uses the real decision table, with only the BOSS HTTP call replaced.
     */
    private static final class StubVerifier extends BossIdentityVerifier {

        private final String responseBody;

        private StubVerifier(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        JSONObject fetchUserInfo(String bossToken) {
            return responseBody == null ? null : JSONUtil.parseObj(responseBody);
        }
    }
}
