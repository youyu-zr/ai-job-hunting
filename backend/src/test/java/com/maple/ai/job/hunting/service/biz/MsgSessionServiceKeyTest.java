package com.maple.ai.job.hunting.service.biz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MsgSessionServiceKeyTest {

    @Test
    void manualStopCacheIsScopedByUserAndJob() {
        assertEquals("42:岗位A", MsgSessionService.scopedStopCacheKey(42L, "岗位A"));
        assertNotEquals(
                MsgSessionService.scopedStopCacheKey(42L, "岗位A"),
                MsgSessionService.scopedStopCacheKey(43L, "岗位A")
        );
        assertNotEquals(
                MsgSessionService.scopedStopCacheKey(42L, "岗位A"),
                MsgSessionService.scopedStopCacheKey(42L, "岗位B")
        );
    }
}
