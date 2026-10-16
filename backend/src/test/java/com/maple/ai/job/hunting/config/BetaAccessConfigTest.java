package com.maple.ai.job.hunting.config;

import com.maple.ai.job.hunting.emums.ProductEnum;
import com.maple.ai.job.hunting.emums.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetaAccessConfigTest {

    @Test
    void configuredDeadlineUsesItsChinaOffsetAndIsExclusive() {
        BetaAccessConfig config = new BetaAccessConfig("2026-12-30T23:59:59.999+08:00");
        Date deadline = Date.from(OffsetDateTime.parse("2026-12-30T23:59:59.999+08:00").toInstant());
        Date beforeDeadline = Date.from(Instant.parse("2026-12-30T15:59:59Z"));

        assertTrue(config.isAutoGrantActive(beforeDeadline));
        assertFalse(config.isAutoGrantActive(deadline));
        assertEquals(deadline, config.resolveExpiry(beforeDeadline));
    }

    @Test
    void missingDeadlineDoesNotGrantAccess() {
        BetaAccessConfig config = new BetaAccessConfig("");

        assertFalse(config.isAutoGrantActive(new Date()));
        assertThrows(IllegalArgumentException.class, () -> config.resolveExpiry(new Date()));
    }

    @Test
    void betaEntitlementIncludesTheFullFeatureSet() {
        assertTrue(ProductEnum.BETA.getProductTypes().contains(ProductTypeEnum.BATCH_PUSH.getCode()));
        assertTrue(ProductEnum.BETA.getProductTypes().contains(ProductTypeEnum.AI_SEAT.getCode()));
        assertTrue(ProductEnum.BETA.getProductTypes().contains(ProductTypeEnum.AI_CUSTOM_MSG.getCode()));
        assertTrue(ProductEnum.BETA.getProductTypes().contains(ProductTypeEnum.AI_FILTER.getCode()));
        assertTrue(ProductEnum.BETA.getProductTypes().contains(ProductTypeEnum.CUSTOM_API.getCode()));
    }
}
