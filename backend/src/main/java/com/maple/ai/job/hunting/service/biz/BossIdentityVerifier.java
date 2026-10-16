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
 * <p>The userscript runs inside the logged-in BOSS page, so it can read the page token
 * ({@code _PAGE.token}) that BOSS issues. We replay that token against a BOSS endpoint and
 * require the uid BOSS reports to equal the uid the caller claimed. Without this check a caller
 * only needed an enumerable BOSS uid to obtain somebody else's session.
 *
 * <p>Infrastructure failures are treated as inconclusive and rejected at the authorization
 * boundary. An unavailable identity provider must not turn a claimed uid plus an unverified
 * token into an authenticated session.
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
     * Outcome of an ownership check.
     */
    private enum Outcome {
        /** Proved: the token is valid and belongs to the claimed uid. */
        MATCH,
        /** Refuted: the token is invalid/expired, or it belongs to a different uid. */
        MISMATCH,
        /** Unresolved: BOSS was unreachable or answered in a shape we do not recognise. */
        INCONCLUSIVE
    }

    /**
     * @return true when the caller may proceed.
     */
    public boolean verify(String claimedUid, String bossToken) {
        if (!enabled) {
            log.error("BOSS identity verification is disabled; rejecting request");
            return false;
        }
        if (StringUtils.isBlank(claimedUid) || StringUtils.isBlank(bossToken)) {
            // A logged-in BOSS page always exposes a token, so its absence is a real failure.
            log.warn("BOSS identity verification rejected: missing uid or token");
            return false;
        }

        Outcome outcome = evaluate(claimedUid, bossToken);
        if (outcome == Outcome.INCONCLUSIVE) {
            log.warn("BOSS identity verification inconclusive; rejecting request");
            return false;
        }
        return outcome == Outcome.MATCH;
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
            log.warn("BOSS rejected the token as expired or invalid");
            return Outcome.MISMATCH;
        }
        if (code != 0) {
            log.warn("BOSS returned an unexpected code={}", code);
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
                .header("User-Agent", "Mozilla/5.0")
                .timeout(TIMEOUT_MILLIS)
                .execute()) {
            if (!response.isOk()) {
                return null;
            }
            return JSONUtil.parseObj(response.body());
        }
    }

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
     * Throws the shared business error when ownership is explicitly refuted.
     */
    public void requireOwnership(String claimedUid, String bossToken) {
        if (!verify(claimedUid, bossToken)) {
            throw new ApplicationException(BizCodeEnum.IDENTITY_VERIFY_FAILED);
        }
    }
}
