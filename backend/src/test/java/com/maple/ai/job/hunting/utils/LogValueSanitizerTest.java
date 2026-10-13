package com.maple.ai.job.hunting.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogValueSanitizerTest {

    @Test
    void removesLineBreaksAndLimitsUntrustedLogFields() {
        assertEquals("job-123 fake=true ...",
                LogValueSanitizer.sanitize("job-123\r\nfake=true\u0000overflow", 21));
    }
}
