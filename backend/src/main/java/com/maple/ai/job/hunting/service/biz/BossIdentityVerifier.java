package com.maple.ai.job.hunting.service.biz;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.maple.ai.job.hunting.emums.BizCodeEnum;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.smart.config.core.annotation.JsonValue;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Verifies that a caller really owns the BOSS account it claims.
 *
 * <p>The userscript runs inside the logged-in BOSS page, so it can read the current {@code bst}
 * cookie that BOSS issues. We replay that token against a BOSS endpoint and
 * require the uid BOSS reports to equal the uid the caller claimed. Without this check a caller
 * only needed an enumerable BOSS uid to obtain somebody else's session.
 *
 * <p>The provider can answer in three different ways, and they do not carry the same meaning:
 * it can name the same uid (proved), name a different uid (refuted — evidence of impersonation),
 * or decline to decide (the credential was not accepted, the call timed out, or the body was
 * unparseable). Declining to decide says nothing about who owns the credential.
 *
 * <p>Strict mode ({@code boss.identity.verify.lenient=false}, the default) refuses both the refuted
 * and the undecidable outcomes. Lenient mode ({@code true}) still refuses the refuted outcome but
 * lets an undecidable one through as an explicitly unverified session. That distinction matters in
 * production: while the provider rejects every credential from this host, treating "undecidable" as
 * "refuted" makes the whole product unusable, and it buys no security because no ownership was ever
 * established.
 *
 * <p>Two outcomes are refused in both modes: a missing uid (nothing to bind the session to) and a
 * missing credential ("the caller sent nothing" is not "the provider could not decide").
 */
@Slf4j
@Service
public class BossIdentityVerifier {

    /**
     * BOSS endpoint that echoes the authenticated user's own uid.
     */
    private static final String BOSS_UID_ENDPOINT = "https://www.zhipin.com/wapi/zpuser/wap/getUserInfo.json";

    private static final int TIMEOUT_MILLIS = 8000;

    /**
     * BOSS response code meaning "not logged in / token expired".
     */
    private static final int BOSS_CODE_NOT_LOGGED_IN = 7;

    /**
     * Identity verification must fail closed when disabled or misconfigured.
     */
    @JsonValue("${boss.identity.verify.enabled:true}")
    private boolean enabled = true;

    /**
     * Whether an undecidable provider answer blocks the session.
     *
     * <p>Defaults to {@code false} (strict), so an unconfigured deployment keeps the original
     * fail-closed behaviour. Only an explicit opt-in relaxes it, and even then a conflicting uid is
     * still refused.
     *
     * <p>Deliberately a boolean, not a mode string: the config annotation used here does not accept
     * String values, and a mistyped string would silently pick one of two very different behaviours.
     */
    @JsonValue("${boss.identity.verify.lenient:false}")
    private boolean lenient = false;

    /**
     * Outcome of an ownership check.
     */
    private enum Outcome {
        /** Proved: the token is valid and belongs to the claimed uid. */
        MATCH,
        /** Refuted: the token is valid but belongs to a different uid. */
        MISMATCH,
        /** Unresolved: the credential was not accepted, BOSS was unreachable, or the shape was unknown. */
        INCONCLUSIVE
    }

    /**
     * What the caller is allowed to do, and whether ownership was actually established.
     */
    public enum Decision {
        /** BOSS named the same uid. */
        VERIFIED,
        /** BOSS declined to decide; the session is issued without an ownership proof. */
        UNVERIFIED,
        /** Refused. */
        DENY
    }

    private boolean lenientMode() {
        return lenient;
    }

    /**
     * @return true when the caller may proceed under the configured mode.
     */
    public boolean verify(String claimedUid, String bossToken) {
        return authorizeLogin(claimedUid, bossToken) != Decision.DENY;
    }

    /**
     * Decides whether a login may proceed, and whether it counts as ownership-proved.
     *
     * <p>A missing uid is always refused: without it there is no account to bind the session to.
     * A conflicting uid is always refused: that is evidence of impersonation, not a gap in
     * evidence. Only "the provider could not tell us" is relaxed under lenient mode.
     */
    public Decision authorizeLogin(String claimedUid, String bossToken) {
        boolean lenient = lenientMode();

        // No uid means there is no account to bind a session to. This is checked before anything
        // else, including the enable switch: a disabled check must not conjure an identity that
        // the caller never claimed.
        String uid = StringUtils.trimToEmpty(claimedUid);
        if (uid.isEmpty()) {
            log.warn("BOSS login refused: no uid supplied");
            return Decision.DENY;
        }

        if (!enabled) {
            // An operator switched the check off on purpose; that is a decision, not a fault.
            // Still not an ownership proof, so it never reports VERIFIED.
            log.warn("BOSS identity verification is disabled; session will be {}", lenient ? "unverified" : "refused");
            return lenient ? Decision.UNVERIFIED : Decision.DENY;
        }

        String token = StringUtils.trimToEmpty(bossToken);
        if (token.isEmpty()) {
            // "The caller sent nothing" is not the same as "the provider could not decide". No
            // credential is a refusal in both modes; otherwise lenient mode would accept literally
            // any uid with an empty token.
            log.warn("BOSS login refused: no credential supplied");
            return Decision.DENY;
        }

        Outcome outcome = evaluate(uid, token);
        return switch (outcome) {
            case MATCH -> Decision.VERIFIED;
            // Impersonation evidence is never relaxed, in either mode.
            case MISMATCH -> {
                log.warn("BOSS login refused: credential belongs to a different uid");
                yield Decision.DENY;
            }
            case INCONCLUSIVE -> undecidable("provider could not decide", lenient);
        };
    }

    private Decision undecidable(String reason, boolean lenient) {
        if (lenient) {
            log.warn("BOSS ownership unverified ({}); issuing unverified session", reason);
            return Decision.UNVERIFIED;
        }
        log.warn("BOSS ownership unverified ({}); refusing request", reason);
        return Decision.DENY;
    }

    private Outcome evaluate(String claimedUid, String bossToken) {
        JSONObject body;
        try {
            body = fetchUserInfo(bossToken);
        } catch (Exception e) {
            log.warn("BOSS identity verification failed; rejecting request (type={})",
                    e.getClass().getSimpleName());
            return Outcome.INCONCLUSIVE;
        }
        if (body == null) {
            return Outcome.INCONCLUSIVE;
        }

        int code = body.getInt("code", -1);
        if (code == BOSS_CODE_NOT_LOGGED_IN) {
            // "当前登录状态已失效" means the credential was not accepted. It is not a statement
            // about who owns it, so it cannot refute ownership. Treating it as MISMATCH is what
            // made every login fail once the provider started rejecting this host's credentials.
            log.warn("BOSS did not accept the credential (code=7); ownership undecidable");
            return Outcome.INCONCLUSIVE;
        }
        if (code != 0) {
            log.warn("BOSS returned an unexpected code={}; ownership undecidable", code);
            return Outcome.INCONCLUSIVE;
        }

        String resolvedUid = extractUid(body.getJSONObject("zpData"));
        if (resolvedUid == null) {
            log.warn("BOSS response contained no recognisable uid");
            return Outcome.INCONCLUSIVE;
        }
        if (StringUtils.equals(StringUtils.trim(claimedUid), StringUtils.trim(resolvedUid))) {
            return Outcome.MATCH;
        }
        log.warn("BOSS identity mismatch");
        return Outcome.MISMATCH;
    }

    /**
     * Package-private so tests can stub the only network call without touching the decision table.
     */
    JSONObject fetchUserInfo(String bossToken) {
        try (HttpResponse response = HttpRequest.get(BOSS_UID_ENDPOINT)
                .header("Zp_token", bossToken)
                .header("Cookie", "bst=" + bossToken)
                .header("Referer", "https://www.zhipin.com/web/geek/job")
                .header("Origin", "https://www.zhipin.com")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("User-Agent", BROWSER_USER_AGENT)
                .timeout(TIMEOUT_MILLIS)
                .execute()) {
            if (!response.isOk()) {
                return null;
            }
            return JSONUtil.parseObj(response.body());
        }
    }

    /**
     * A bare {@code Mozilla/5.0} is not sent by any real browser and is a well-known bot marker.
     */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";

    /**
     * BOSS has used several spellings for this field across endpoints, so accept the known ones.
     */
    String extractUid(JSONObject zpData) {
        if (zpData == null) {
            return null;
        }
        for (String key : new String[]{"uid", "userId", "userid", "encryptUserId", "geekId"}) {
            Object value = zpData.get(key);
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /**
     * Throws the shared business error when the login is refused under the configured mode.
     *
     * @return whether ownership was actually proved
     */
    public boolean authorizeLoginOrThrow(String claimedUid, String bossToken) {
        Decision decision = authorizeLogin(claimedUid, bossToken);
        if (decision == Decision.DENY) {
            throw new ApplicationException(BizCodeEnum.IDENTITY_VERIFY_FAILED);
        }
        return decision == Decision.VERIFIED;
    }

    /**
     * Throws the shared business error unless ownership was actually proved.
     *
     * <p>Unlike {@link #authorizeLoginOrThrow}, this does not honour lenient mode: an unverified
     * session is not enough. Use it where the caller needs proof, not just admission.
     */
    public void requireOwnership(String claimedUid, String bossToken) {
        if (authorizeLogin(claimedUid, bossToken) != Decision.VERIFIED) {
            throw new ApplicationException(BizCodeEnum.IDENTITY_VERIFY_FAILED);
        }
    }
}
