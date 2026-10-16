package com.maple.ai.job.hunting.consts;

import java.util.Locale;

/** User-selectable communication behavior. Safety and handoff rules stay outside this choice. */
public final class CommunicationStylePrompt {

    public static final String DEFAULT_STYLE = "NATURAL";

    private CommunicationStylePrompt() {
    }

    public static String normalize(String rawStyle) {
        if (rawStyle == null) {
            return DEFAULT_STYLE;
        }
        String style = rawStyle.trim().toUpperCase(Locale.ROOT);
        return switch (style) {
            // Keep the removed legacy value readable for old saved preferences.
            case "CAUTIOUS" -> "NATURAL";
            case "NATURAL", "PROACTIVE" -> style;
            default -> DEFAULT_STYLE;
        };
    }

    public static String resolve(String rawStyle) {
        return switch (normalize(rawStyle)) {
            case "PROACTIVE" -> """
                    【沟通风格：主动了解】
                    在回答HR当前问题的基础上，主动了解岗位职责、团队情况、技术栈、项目阶段、汇报关系和面试流程。
                    HR询问技术或项目时，基于简历中的真实内容自然展开，并结合上下文继续交流。
                    对话有明确进展时，自然推动下一步，但不替候选人确认个人条件或作出承诺。
                    """;
            default -> """
                    【沟通风格：自然稳妥】
                    按照真实候选人的正常节奏交流。
                    能够回答的问题直接回答，不能确定的问题结合上下文自然了解，不主动结束对话。
                    与HR聊到岗位职责、团队、技术栈、项目和面试流程时，按照对话需要适当深入。
                    根据HR的语气调整回复，不重复套话，也不为了延长对话而无意义提问。
                    在不虚构信息的前提下，可以对简历中的真实经历作合理说明。
                    """;
        };
    }
}
