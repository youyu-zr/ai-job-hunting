package com.maple.ai.job.hunting.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/** Removes obsolete response-shape directives from user-authored prompt data. */
public final class PromptDataSanitizer {

    private static final Pattern UNBOUNDED_LENGTH_DIRECTIVE = Pattern.compile(
            "(?:没有|无|不设|不固定|不限制)\\s*(?:固定\\s*)?(?:句数|字数|追问轮数|追问次数)"
                    + "(?:\\s*[、,，和及与]\\s*(?:固定\\s*)?(?:句数|字数|追问轮数|追问次数))*"
                    + "[，,。；;]?"
    );
    private static final Pattern NAMED_LENGTH_DIRECTIVE = Pattern.compile(
            "固定\\s*(?:句数|字数|追问轮数|追问次数)[^，,。；;\\r\\n]*[，,。；;]?"
    );
    private static final Pattern NUMERIC_LENGTH_DIRECTIVE = Pattern.compile(
            "(?:普通交流|日常交流|正常交流|一般回复|回复|回答|生成|控制|限制|保持|最多|不超过)"
                    + "[^，,。；;\\r\\n]{0,30}"
                    + "(?:\\d+\\s*[-~～至到]\\s*\\d+|\\d+|一到[两三四五六七八九十]|一两|两三|三四)"
                    + "(?:句|字|轮|次)[^，,。；;\\r\\n]*[，,。；;]?"
    );
    private static final Pattern FOLLOW_UP_DIRECTIVE = Pattern.compile(
            "(?:固定|最多|不超过|限制|控制)?\\s*追问(?:轮数|次数|数量)?"
                    + "[^，,。；;\\r\\n]{0,20}"
                    + "(?:\\d+\\s*[-~～至到]\\s*\\d+|\\d+|一到[两三四五六七八九十]|一两|两三|三四)"
                    + "(?:轮|次)[^，,。；;\\r\\n]*[，,。；;]?"
    );

    private PromptDataSanitizer() {
    }

    public static String removeLegacyReplyLengthDirectives(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String normalized = raw.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]+", " ");
        normalized = UNBOUNDED_LENGTH_DIRECTIVE.matcher(normalized).replaceAll("");
        normalized = NAMED_LENGTH_DIRECTIVE.matcher(normalized).replaceAll("");
        normalized = FOLLOW_UP_DIRECTIVE.matcher(normalized).replaceAll("");
        normalized = NUMERIC_LENGTH_DIRECTIVE.matcher(normalized).replaceAll("");
        return normalized.replaceAll("[ \\t]{2,}", " ")
                .replaceAll("[ \\t]*[；;][ \\t]*", "；")
                .replaceAll("(?:^|[；;])\\s*[，,。！？!?]", "")
                .replaceAll("^[；;、，,。！？!?\\s]+|[；;、，,。！？!?\\s]+$", "")
                .trim();
    }
}
