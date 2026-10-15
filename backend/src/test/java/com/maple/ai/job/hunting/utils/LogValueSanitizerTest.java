package com.maple.ai.job.hunting.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogValueSanitizerTest {

    @Test
    void masksEmailAndFormattedMobileNumbersFromUntrustedValues() {
        assertEquals("联系[手机号已隐藏]，[手机号已隐藏]，[邮箱已隐藏]",
                LogValueSanitizer.sanitize(
                        "联系138.0013.8000，１３８００１３８０００，jane@example.com", 200));
    }

    @Test
    void removesLineBreaksAndLimitsUntrustedLogFields() {
        assertEquals("job-123 fake=true ...",
                LogValueSanitizer.sanitize("job-123\r\nfake=true\u0000overflow", 21));
    }
}
