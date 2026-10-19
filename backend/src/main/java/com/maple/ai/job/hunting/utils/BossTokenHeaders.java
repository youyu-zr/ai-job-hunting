package com.maple.ai.job.hunting.utils;

import com.maple.ai.job.hunting.emums.BizCodeEnum;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import org.apache.commons.lang3.StringUtils;

public final class BossTokenHeaders {

    public static final String CANONICAL_HEADER = "X-Boss-Token";
    public static final String LEGACY_HEADER = "Zp_token";

    private BossTokenHeaders() {
    }

    public static String resolve(String canonical, String legacy) {
        boolean hasCanonical = StringUtils.isNotBlank(canonical);
        boolean hasLegacy = StringUtils.isNotBlank(legacy);
        if (hasCanonical && hasLegacy && !canonical.equals(legacy)) {
            throw new ApplicationException(BizCodeEnum.IDENTITY_VERIFY_FAILED);
        }
        return hasCanonical ? canonical : hasLegacy ? legacy : null;
    }
}
