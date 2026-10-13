package com.maple.ai.job.hunting.utils;

public final class LogValueSanitizer {

    private LogValueSanitizer() {
    }

    public static String sanitize(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
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
}
