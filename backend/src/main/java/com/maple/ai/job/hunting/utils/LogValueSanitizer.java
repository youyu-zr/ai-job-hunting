package com.maple.ai.job.hunting.utils;

import java.util.regex.Pattern;

public final class LogValueSanitizer {

    // Exclude every local-part character to prevent rescanning suffixes after a failed match.
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "(?i)(?<![\\w.%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![\\w.-])");
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
        String withoutEmail = EMAIL_ADDRESS.matcher(value).replaceAll("[邮箱已隐藏]");
        return CN_MOBILE_NUMBER.matcher(withoutEmail).replaceAll("[手机号已隐藏]");
    }
}
