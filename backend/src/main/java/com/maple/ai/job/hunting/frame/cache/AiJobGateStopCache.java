package com.maple.ai.job.hunting.frame.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Stop state owned by the per-job AI gate, separate from manual stop state. */
@Component
public class AiJobGateStopCache extends AbsMemoryCache<String, Boolean> {
    public AiJobGateStopCache() {
        super(6, TimeUnit.HOURS, false);
    }
}
