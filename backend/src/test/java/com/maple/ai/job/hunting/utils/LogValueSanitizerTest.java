package com.maple.ai.job.hunting.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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

    @ParameterizedTest
    @MethodSource("emailBoundaryCases")
    void preservesEmailRedactionBoundaries(String input, String expected) {
        assertEquals(expected, LogValueSanitizer.sanitizeSensitiveText(input));
    }

    private static Stream<Arguments> emailBoundaryCases() {
        String hidden = "[邮箱已隐藏]";
        return Stream.of(
                Arguments.of("", ""),
                Arguments.of("user@example.com", hidden),
                Arguments.of("USER@EXAMPLE.COM", hidden),
                Arguments.of("._%+-@sub-domain..EXAMPLE.co.uk", hidden),
                Arguments.of("<first@example.com>,second@example.org!", "<" + hidden + ">," + hidden + "!"),
                Arguments.of("x@example.co/y@example.org", hidden + "/" + hidden),
                Arguments.of("x@example.co@y@example.org", hidden + "@" + hidden),
                Arguments.of("x@example.co%y@example.org", hidden + "%y@example.org"),
                Arguments.of("x@example.co+y@example.org", hidden + "+y@example.org"),
                Arguments.of("x@example.co% y@example.org", hidden + "% " + hidden),
                Arguments.of("invalid@valid@example.org", "invalid@" + hidden),
                Arguments.of("invalid@one.c@host.net", "invalid@" + hidden),
                Arguments.of("invalid@host.com_suffix@next.org", "invalid@" + hidden),
                Arguments.of("first@example.com;@;second@example.org", hidden + ";@;" + hidden),
                Arguments.of("x@a.co", hidden),
                Arguments.of("x@..co", hidden),
                Arguments.of("x@-.co", hidden),
                Arguments.of("x@example.aa", hidden),
                Arguments.of("@example.com", "@example.com"),
                Arguments.of("x@.co", "x@.co"),
                Arguments.of("x@example.c", "x@example.c"),
                Arguments.of("x@example.co_", "x@example.co_"),
                Arguments.of("x@example.co9", "x@example.co9"),
                Arguments.of("x@example.co-", "x@example.co-"),
                Arguments.of("x@example.co.", "x@example.co."),
                Arguments.of("x@example.co.uk_", "x@example.co.uk_"),
                Arguments.of("x@example.c0m", "x@example.c0m"),
                Arguments.of("x@example.com\u00e9", hidden + "\u00e9"),
                Arguments.of("\u4e2dx@example.com\u4e2d", "\u4e2d" + hidden + "\u4e2d"),
                Arguments.of("\uff58@example.com", "\uff58@example.com"),
                Arguments.of("x@example\uff0ecom", "x@example\uff0ecom"),
                Arguments.of("x@example.c\u0130", "x@example.c\u0130"));
    }

    @Test
    void retainsNullEmptyAndSmallOutputLimitsWhenRedactingEmail() {
        assertEquals("", LogValueSanitizer.sanitizeSensitiveText(null));
        assertEquals("", LogValueSanitizer.sanitize("user@example.com", 0));
        assertEquals("", LogValueSanitizer.sanitize("user@example.com", -1));
        assertEquals("", LogValueSanitizer.sanitize(null, 20));
        assertEquals("[", LogValueSanitizer.sanitize("user@example.com", 1));
        assertEquals("[邮", LogValueSanitizer.sanitize("user@example.com", 2));
        assertEquals("[邮箱", LogValueSanitizer.sanitize("user@example.com", 3));
        assertEquals("[...", LogValueSanitizer.sanitize("user@example.com", 4));
        assertEquals("[邮箱已隐藏]", LogValueSanitizer.sanitize("user@example.com", 10));
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
            String letters = "a".repeat(200_000);
            String invalidSuffix = "user@" + letters + "." + letters + "_";
            checkSanitized(invalidSuffix, invalidSuffix);
            String invalidLocalChain = (letters + "@").repeat(8);
            checkSanitized(invalidLocalChain, invalidLocalChain);
            checkSanitized("[邮箱已隐藏]",
                    letters + "@" + "a.".repeat(100_000) + "co");
            checkSanitized("[邮箱已隐藏];".repeat(20_000),
                    "u@example.com;".repeat(20_000));
            checkSanitized("invalid@".repeat(20_000) + "[邮箱已隐藏]",
                    "invalid@".repeat(20_000) + "user@example.com");
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
