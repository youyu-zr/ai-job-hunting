package com.maple.ai.job.hunting.service.biz;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maple.ai.job.hunting.consts.AIPromptStrConstant;
import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Action;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Category;
import com.maple.ai.job.hunting.service.ai.AIServiceFacade;
import com.maple.ai.job.hunting.utils.LogValueSanitizer;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class MessageTriageService {

    private static final int MAX_MESSAGE_LENGTH = 5_000;
    private static final int MAX_JOB_CONTEXT_LENGTH = 6_000;
    private static final int MAX_RESUME_LENGTH = 8_000;
    private static final int MAX_PREFERENCE_LENGTH = 5_000;
    private static final int MAX_MODEL_RESPONSE_LENGTH = 8_000;
    private static final int MAX_REASON_CODE_LENGTH = 64;
    private static final int MAX_REASON_LENGTH = 240;
    private static final int MAX_QUESTION_COUNT = 4;
    private static final int MAX_QUESTION_LENGTH = 180;
    private static final int MAX_EVIDENCE_COUNT = 5;
    private static final int MAX_EVIDENCE_LENGTH = 240;
    private static final ObjectMapper SAFE_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final List<String> ALLOWED_RESPONSE_FIELDS = List.of(
            "category", "action", "reasonCode", "plainLanguageReason",
            "clarificationQuestions", "evidence");

    @Resource
    private AIServiceFacade aiServiceFacade;

    public MessageTriageResultVO classify(String question,
                                          String jobContext,
                                          String resumeContent,
                                          PreferenceEntity preference,
                                          Integer platformBodyType) {
        MessageTriageResultVO platformResult = platformResult(platformBodyType);
        if (platformResult != null) {
            return platformResult;
        }

        String safeQuestion = normalize(question);
        if (safeQuestion.isEmpty() || safeQuestion.length() > MAX_MESSAGE_LENGTH) {
            return processingError();
        }

        PreferenceEntity safePreference = preference == null ? new PreferenceEntity() : preference;
        try {
            String userPayload = buildUserPayload(
                    safeQuestion,
                    limit(jobContext, MAX_JOB_CONTEXT_LENGTH),
                    limit(resumeContent, MAX_RESUME_LENGTH),
                    limit(serializePreference(safePreference), MAX_PREFERENCE_LENGTH));
            if (StringUtils.isBlank(userPayload)) {
                return processingError();
            }
            String modelResponse = aiServiceFacade.askAndAnswer(
                    AIPromptStrConstant.AI_MESSAGE_TRIAGE_PROMPT,
                    userPayload);
            ParsedDecision decision = parseResponse(modelResponse);
            if (decision == null) {
                return processingError();
            }
            if (decision.category() == Category.PROCESSING_ERROR) {
                return processingError();
            }

            Action action = resolveAction(
                    decision.category(), decision.action(), safePreference.isMarketingReplyE());
            if (action == null) {
                return processingError();
            }
            return MessageTriageResultVO.builder()
                    .category(decision.category())
                    .action(action)
                    .reasonCode(decision.reasonCode())
                    .plainLanguageReason(sanitizeOutput(decision.reason()))
                    .clarificationQuestions(sanitizeOutput(decision.questions()))
                    .evidence(sanitizeOutput(decision.evidence()))
                    .build();
        } catch (Exception ignored) {
            return processingError();
        }
    }

    private static MessageTriageResultVO platformResult(Integer platformBodyType) {
        if (platformBodyType == null) {
            return null;
        }
        if (List.of(4, 8, 12).contains(platformBodyType)) {
            return result(Category.PLATFORM_EVENT, Action.SILENT_CURRENT_MESSAGE,
                    "PLATFORM_EVENT", "这是平台事件，不是需要回复的HR消息。");
        }
        if (platformBodyType == 14) {
            return result(Category.PLATFORM_EVENT, Action.NEEDS_USER_CONFIRMATION,
                    "INTERVIEW_CARD_REQUIRES_CONFIRMATION", "平台面试卡片需要本人确认。");
        }
        return null;
    }

    private static MessageTriageResultVO result(Category category,
                                                Action action,
                                                String reasonCode,
                                                String reason) {
        return MessageTriageResultVO.builder()
                .category(category)
                .action(action)
                .reasonCode(reasonCode)
                .plainLanguageReason(reason)
                .clarificationQuestions(List.of())
                .evidence(List.of())
                .build();
    }

    static String buildUserPayload(String question, String jobContext, String resumeContent, String preference) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("question", question);
            payload.put("jobContext", jobContext);
            payload.put("resumeContent", resumeContent);
            payload.put("preference", SAFE_JSON.readTree(preference));
            return SAFE_JSON.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static ParsedDecision parseResponse(String response) {
        JsonNode json = firstJsonObject(response);
        if (json == null || json.has("@type")) {
            return null;
        }
        var fields = json.fieldNames();
        while (fields.hasNext()) {
            if (!ALLOWED_RESPONSE_FIELDS.contains(fields.next())) {
                return null;
            }
        }

        Category category = enumField(json, "category", Category.class);
        Action action = enumField(json, "action", Action.class);
        String reasonCode = textField(json, "reasonCode", MAX_REASON_CODE_LENGTH);
        String reason = textField(json, "plainLanguageReason", MAX_REASON_LENGTH);
        List<String> questions = stringList(json.get("clarificationQuestions"),
                MAX_QUESTION_COUNT, MAX_QUESTION_LENGTH);
        List<String> evidence = stringList(json.get("evidence"), MAX_EVIDENCE_COUNT, MAX_EVIDENCE_LENGTH);
        if (category == null || action == null || reasonCode == null || reason == null
                || !REASON_CODE.matcher(reasonCode).matches() || questions == null || evidence == null) {
            return null;
        }
        return new ParsedDecision(category, action, reasonCode, reason, questions, evidence);
    }

    private static Action resolveAction(Category category, Action requested, boolean marketingReplyEnabled) {
        return switch (category) {
            case NORMAL_JOB -> requested == Action.REPLY || requested == Action.NEEDS_USER_CONFIRMATION
                    ? requested : null;
            case PROMOTION -> {
                if (requested != Action.REPLY && requested != Action.SILENT_CURRENT_MESSAGE) {
                    yield null;
                }
                yield marketingReplyEnabled && requested == Action.REPLY
                        ? Action.REPLY : Action.SILENT_CURRENT_MESSAGE;
            }
            case HIGH_RISK -> Action.BLOCK_CURRENT_JOB;
            case UNCERTAIN -> requested == Action.REPLY ? Action.REPLY : null;
            case PLATFORM_EVENT -> null;
            case PROCESSING_ERROR -> null;
        };
    }

    private static <T extends Enum<T>> T enumField(JsonNode json, String name, Class<T> enumType) {
        JsonNode node = json.get(name);
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, node.textValue());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String textField(JsonNode json, String name, int maxLength) {
        JsonNode node = json.get(name);
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = normalize(node.textValue());
        return value.isEmpty() || value.length() > maxLength ? null : value;
    }

    private static List<String> stringList(JsonNode node, int maxCount, int maxLength) {
        if (node == null || !node.isArray() || node.size() > maxCount) {
            return null;
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                return null;
            }
            String value = normalize(item.textValue());
            if (value.isEmpty() || value.length() > maxLength) {
                return null;
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static String sanitizeOutput(String value) {
        return LogValueSanitizer.sanitizeSensitiveText(value);
    }

    private static List<String> sanitizeOutput(List<String> values) {
        return values.stream().map(MessageTriageService::sanitizeOutput).toList();
    }

    private static JsonNode firstJsonObject(String response) {
        if (StringUtils.isBlank(response) || response.length() > MAX_MODEL_RESPONSE_LENGTH) {
            return null;
        }
        String text = response.trim();
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                try {
                    String jsonObject = text.substring(start, i + 1);
                    JsonNode root = SAFE_JSON.readTree(jsonObject);
                    String suffix = text.substring(i + 1).trim();
                    if (suffix.indexOf('{') >= 0 || suffix.indexOf('[') >= 0) {
                        return null;
                    }
                    return root != null && root.isObject() ? root : null;
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String serializePreference(PreferenceEntity preference) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("jobGoal", preference.getJobGoal());
        values.put("unacceptableConditions", preference.getUnacceptableConditions());
        values.put("clarifyConditions", preference.getClarifyConditions());
        values.put("communicationPreferences", preference.getCommunicationPreferences());
        values.put("communicationStyle", preference.getCommunicationStyle());
        values.put("marketingReplyE", preference.isMarketingReplyE());
        values.put("promotionReplyPolicy", preference.getPromotionReplyPolicy());
        values.put("salaryRange", preference.getSr());
        values.put("resumeCity", preference.getResumeCity());
        values.put("targetCities", preference.getTargetCities());
        try {
            String json = SAFE_JSON.writeValueAsString(values);
            if (json.length() <= MAX_PREFERENCE_LENGTH) {
                return json;
            }
            throw new IllegalArgumentException("Preference payload exceeds supported limit");
        } catch (Exception ignored) {
            throw new IllegalStateException("Unable to serialize classifier preferences", ignored);
        }
    }

    private static String limit(String value, int maxLength) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return "未提供";
        }
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        String marker = "…内容已截断";
        int contentLength = Math.max(0, maxLength - marker.length());
        return normalized.substring(0, contentLength) + marker;
    }

    private static String normalize(String value) {
        return StringUtils.defaultString(value)
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]+", " ")
                .trim();
    }

    private static MessageTriageResultVO processingError() {
        return result(Category.PROCESSING_ERROR, Action.NO_ACTION_RETRY,
                "AI_PROCESSING_ERROR", "消息暂时无法安全分析，请稍后重试。");
    }

    private record ParsedDecision(Category category,
                                  Action action,
                                  String reasonCode,
                                  String reason,
                                  List<String> questions,
                                  List<String> evidence) {
    }
}
