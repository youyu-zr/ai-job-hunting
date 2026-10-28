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
 * Covers the BOSS identity-forgery guard used when issuing /api/user/silently/login sessions.
 * Resume import separately requires that server-issued application session.
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

        // Default mode is strict, so an undecidable answer still refuses the login.
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

    // ---------------------------------------------------------------------------------------
    // Mode behaviour. The distinction that matters: "BOSS could not tell us" is not the same
    // answer as "BOSS named somebody else", and only the former is relaxable.
    // ---------------------------------------------------------------------------------------

    @Test
    void anUndecidableAnswerIsUnverifiedRatherThanRefutedUnderLenientMode() {
        StubVerifier verifier = new StubVerifier("{\"code\":7,\"message\":\"当前登录状态已失效\",\"zpData\":{}}");
        ReflectionTestUtils.setField(verifier, "lenient", true);

        assertEquals(BossIdentityVerifier.Decision.UNVERIFIED, verifier.authorizeLogin("100000001", "stale-token"));
        assertTrue(verifier.verify("100000001", "stale-token"));
    }

    @Test
    void aConflictingUidIsStillRefusedUnderLenientMode() {
        // This is the security boundary that lenient mode must not move: BOSS answered, and the
        // answer names a different account. That is evidence of impersonation, not a gap.
        StubVerifier verifier = new StubVerifier("{\"code\":0,\"zpData\":{\"uid\":\"100000002\"}}");
        ReflectionTestUtils.setField(verifier, "lenient", true);

        assertEquals(BossIdentityVerifier.Decision.DENY, verifier.authorizeLogin("100000001", "victim-token"));
        assertFalse(verifier.verify("100000001", "victim-token"));
        assertThrows(ApplicationException.class,
                () -> verifier.requireOwnership("100000001", "victim-token"));
    }

    @Test
    void aMatchingUidIsVerifiedUnderLenientMode() {
        StubVerifier verifier = new StubVerifier("{\"code\":0,\"zpData\":{\"uid\":\"100000001\"}}");
        ReflectionTestUtils.setField(verifier, "lenient", true);

        assertEquals(BossIdentityVerifier.Decision.VERIFIED, verifier.authorizeLogin("100000001", "valid-token"));
    }

    @Test
    void aMissingUidIsRefusedEvenUnderLenientMode() {
        // Without a uid there is no account to bind a session to, in either mode.
        StubVerifier verifier = new StubVerifier("{\"code\":0,\"zpData\":{\"uid\":\"100000001\"}}");
        ReflectionTestUtils.setField(verifier, "lenient", true);

        assertEquals(BossIdentityVerifier.Decision.DENY, verifier.authorizeLogin(null, "valid-token"));
        assertEquals(BossIdentityVerifier.Decision.DENY, verifier.authorizeLogin("   ", "valid-token"));
    }

    @Test
    void aMissingCredentialIsRefusedInBothModes() {
        // "The caller sent nothing" is not "the provider could not decide". Lenient mode relaxes a
        // gap in the provider's evidence, not the absence of any credential at all.
        StubVerifier lenient = new StubVerifier("{\"code\":0,\"zpData\":{\"uid\":\"100000001\"}}");
        ReflectionTestUtils.setField(lenient, "lenient", true);
        assertEquals(BossIdentityVerifier.Decision.DENY, lenient.authorizeLogin("100000001", ""));

        StubVerifier strict = new StubVerifier("{\"code\":0,\"zpData\":{\"uid\":\"100000001\"}}");
        assertEquals(BossIdentityVerifier.Decision.DENY, strict.authorizeLogin("100000001", ""));
    }

    @Test
    void disabledVerificationNeverIssuesAVerifiedSessionAndStillNeedsAUid() {
        StubVerifier verifier = new StubVerifier("{\"code\":0,\"zpData\":{\"uid\":\"100000001\"}}");
        ReflectionTestUtils.setField(verifier, "lenient", true);
        ReflectionTestUtils.setField(verifier, "enabled", false);

        // Lenient + disabled is an unverified session, never a verified one.
        assertEquals(BossIdentityVerifier.Decision.UNVERIFIED,
                verifier.authorizeLogin("100000001", "any-token"));
        // And a disabled check must not invent an identity the caller never claimed.
        assertEquals(BossIdentityVerifier.Decision.DENY, verifier.authorizeLogin("", "any-token"));
        assertEquals(BossIdentityVerifier.Decision.DENY, verifier.authorizeLogin(null, "any-token"));
    }

    @Test
    void requireOwnershipStillDemandsProofUnderLenientMode() {
        StubVerifier verifier = new StubVerifier("{\"code\":7,\"message\":\"当前登录状态已失效\",\"zpData\":{}}");
        ReflectionTestUtils.setField(verifier, "lenient", true);

        // Admission via lenient is not proof; a caller asking for proof must still be refused.
        assertEquals(BossIdentityVerifier.Decision.UNVERIFIED,
                verifier.authorizeLogin("100000001", "stale-token"));
        assertThrows(ApplicationException.class,
                () -> verifier.requireOwnership("100000001", "stale-token"));
    }

    @Test
    void networkFailureIsUnverifiedUnderLenientMode() {
        BossIdentityVerifier verifier = new BossIdentityVerifier() {
            @Override
            JSONObject fetchUserInfo(String bossToken) {
                throw new IllegalStateException("synthetic network failure");
            }
        };
        ReflectionTestUtils.setField(verifier, "lenient", true);

        assertEquals(BossIdentityVerifier.Decision.UNVERIFIED, verifier.authorizeLogin("100000001", "some-token"));
    }

    @Test
    void theDefaultIsStrictSoAnUnconfiguredDeploymentFailsClosed() {
        StubVerifier verifier = new StubVerifier("{\"code\":7,\"message\":\"当前登录状态已失效\",\"zpData\":{}}");

        // No reflection: this is the field's initial value, which is what an unconfigured
        // deployment actually gets.
        assertEquals(BossIdentityVerifier.Decision.DENY, verifier.authorizeLogin("100000001", "stale-token"));
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
