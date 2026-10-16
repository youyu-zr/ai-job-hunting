package com.maple.ai.job.hunting.utils;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void processesRepeatedPercentInputWithinBoundedChildProcess() throws Exception {
        String classPath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classPath, PercentInputProbe.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS),
                    "Repeated-percent sanitization exceeded the 5-second child-process limit");
            assertEquals(0, process.exitValue(), "Child-process sanitization assertions failed");
        } finally {
            // A regex match may ignore interrupts, so terminate the isolated JVM on timeout.
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Sanitizer probe did not terminate");
        }
    }

    @Test
    void masksPercentLocalPartsAndAdjacentEmailAddresses() {
        assertEquals("[邮箱已隐藏];[邮箱已隐藏] [邮箱已隐藏]",
                LogValueSanitizer.sanitizeSensitiveText(
                        "first.last+tag%dept@sub.example.co.uk;%%%user@example.com USER@EXAMPLE.COM"));
    }

    @Test
    void preservesMalformedAddressesAndNonEmailText() {
        String value = "%%%% no address; jane@example.c; jane@example.com_";
        assertEquals(value, LogValueSanitizer.sanitizeSensitiveText(value));
    }

    @Test
    void masksLongEmailBeforeTruncatingLogFields() {
        assertEquals("[邮箱已隐藏]",
                LogValueSanitizer.sanitize("a".repeat(10_000) + "@example.com", 100));
    }

    @Test
    void masksCountryPrefixedAndFullWidthFormattedMobileNumbers() {
        assertEquals("[手机号已隐藏];[手机号已隐藏];[手机号已隐藏]",
                LogValueSanitizer.sanitizeSensitiveText(
                        "+86 138-0013-8000;86.139.0013.9000;１３８．００１３－８０００"));
    }

    @Test
    void removesLogLineInjectionAfterMaskingPrivateValues() {
        assertEquals("[邮箱已隐藏] [手机号已隐藏] forged=true end",
                LogValueSanitizer.sanitize(
                        "jane@example.com\r\n+86 138-0013-8000\u0000forged=true\tend", 200));
    }

    public static final class PercentInputProbe {

        public static void main(String[] args) {
            String percentRun = "%".repeat(200_000);
            checkSanitized(percentRun, percentRun);
            checkSanitized("[邮箱已隐藏]", percentRun + "@example.com");
            String malformedAddress = percentRun + "@example.c";
            checkSanitized(malformedAddress, malformedAddress);
            String dottedDomain = "user@" + "a.".repeat(100_000) + "!";
            checkSanitized(dottedDomain, dottedDomain);
            if (!("%".repeat(18) + "...").equals(LogValueSanitizer.sanitize(percentRun, 21))) {
                throw new AssertionError("Repeated-percent log field was not safely truncated");
            }
        }

        private static void checkSanitized(String expected, String input) {
            if (!expected.equals(LogValueSanitizer.sanitizeSensitiveText(input))) {
                throw new AssertionError("Adversarial input was not sanitized as expected");
            }
        }
    }
}
