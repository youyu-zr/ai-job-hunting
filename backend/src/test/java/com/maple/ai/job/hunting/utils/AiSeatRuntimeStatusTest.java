package com.maple.ai.job.hunting.utils;

import com.maple.ai.job.hunting.model.vo.AiSeatRuntimeStatusVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiSeatRuntimeStatusTest {

    @Test
    void closedGlobalSeatIsReportedAsClosed() {
        assertEquals("CLOSED", AiSeatRuntimeStatusVO.resolve(false, false, false, false).getStatus());
    }

    @Test
    void enabledSeatWithoutSessionIsWaitingToStart() {
        assertEquals("WAITING", AiSeatRuntimeStatusVO.resolve(true, false, false, false).getStatus());
    }

    @Test
    void manualOrGateStopIsReportedAsPaused() {
        assertEquals("PAUSED", AiSeatRuntimeStatusVO.resolve(true, true, true, false).getStatus());
        assertEquals("PAUSED", AiSeatRuntimeStatusVO.resolve(true, true, false, true).getStatus());
    }

    @Test
    void activeSessionIsReportedAsRunning() {
        assertEquals("RUNNING", AiSeatRuntimeStatusVO.resolve(true, true, false, false).getStatus());
    }
}
