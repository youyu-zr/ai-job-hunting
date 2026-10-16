package com.maple.ai.job.hunting.utils;

import java.util.regex.Pattern;

public final class LogValueSanitizer {

    private static final Pattern CN_MOBILE_NUMBER = Pattern.compile(
            "(?<![\\p{Nd}])(?:\\+?86[\\s.．\\-－—]*)?[1１][3-9３-９](?:[\\s.．\\-－—]*[0-9０-９]){9}(?![\\p{Nd}])");

    private LogValueSanitizer() {
    }

    public static String sanitize(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        value = sanitizeSensitiveText(value);
        StringBuilder safe = new StringBuilder(Math.min(value.length(), maxLength));
        int contentLimit = maxLength >= 4 ? maxLength - 3 : maxLength;
        boolean truncated = false;
        for (int i = 0; i < value.length(); i++) {
            if (safe.length() >= contentLimit) {
                truncated = true;
                break;
            }
            char ch = value.charAt(i);
            if (Character.isISOControl(ch)) {
                if (safe.length() > 0 && safe.charAt(safe.length() - 1) != ' ') {
                    safe.append(' ');
                }
            } else {
                safe.append(ch);
            }
        }
        if (truncated && maxLength >= 4) {
            safe.append("...");
        }
        return safe.toString().trim();
    }

    public static String sanitizeSensitiveText(String value) {
        if (value == null) {
            return "";
        }
        String withoutEmail = redactEmails(value);
        return CN_MOBILE_NUMBER.matcher(withoutEmail).replaceAll("[手机号已隐藏]");
    }

    private static String redactEmails(String value) {
        StringBuilder redacted = null;
        int copiedThrough = 0;
        int index = 0;
        while (index < value.length()) {
            if (!isEmailLocalCharacter(value.charAt(index))) {
                index++;
                continue;
            }
            int localStart = index;
            while (index < value.length() && isEmailLocalCharacter(value.charAt(index))) {
                index++;
            }
            // Check original boundaries even after a preceding address was redacted.
            if (index == value.length() || value.charAt(index) != '@'
                    || (localStart > 0 && isEmailLocalCharacter(value.charAt(localStart - 1)))) {
                continue;
            }

            // Domain lookaheads cannot cross '@', so their scanned ranges are disjoint.
            int domainStart = index + 1;
            int domainEnd = domainStart;
            while (domainEnd < value.length() && isEmailDomainCharacter(value.charAt(domainEnd))) {
                domainEnd++;
            }
            int suffixStart = domainEnd;
            while (suffixStart > domainStart && isAsciiLetter(value.charAt(suffixStart - 1))) {
                suffixStart--;
            }
            boolean validDomain = suffixStart > domainStart + 1
                    && domainEnd - suffixStart >= 2
                    && value.charAt(suffixStart - 1) == '.';
            if (!validDomain || (domainEnd < value.length() && value.charAt(domainEnd) == '_')) {
                index = domainStart;
                continue;
            }

            if (redacted == null) {
                redacted = new StringBuilder(value.length());
            }
            redacted.append(value, copiedThrough, localStart).append("[邮箱已隐藏]");
            copiedThrough = domainEnd;
            index = domainEnd;
        }
        return redacted == null ? value : redacted.append(value, copiedThrough, value.length()).toString();
    }

    private static boolean isEmailLocalCharacter(char value) {
        return isAsciiLetter(value) || (value >= '0' && value <= '9')
                || value == '.' || value == '_' || value == '%' || value == '+' || value == '-';
    }

    private static boolean isEmailDomainCharacter(char value) {
        return isAsciiLetter(value) || (value >= '0' && value <= '9') || value == '.' || value == '-';
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }
}
