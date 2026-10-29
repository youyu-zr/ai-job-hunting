package com.maple.ai.job.hunting.service.biz;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.maple.ai.job.hunting.utils.StructuredPreferenceText;
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
    private static final ObjectReader DECISION_JSON = SAFE_JSON.reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final List<String> ALLOWED_RESPONSE_FIELDS = List.of(
            "category", "action", "reasonCode", "plainLanguageReason",
            "clarificationQuestions", "evidence");

    @Resource
    private AIServiceFacade aiServiceFacade;

    public enum DiagnosticCode {
        INPUT_REJECTED,
        PAYLOAD_REJECTED,
        MODEL_CALL_FAILED,
        RESPONSE_EMPTY,
        RESPONSE_TOO_LARGE,
        RESPONSE_JSON_INVALID,
        RESPONSE_JSON_INVALID_REPAIR_REQUESTED,
        RESPONSE_FIELDS_INVALID,
        RESPONSE_CATEGORY_INVALID,
        RESPONSE_ACTION_INVALID,
        RESPONSE_REASON_CODE_INVALID,
        RESPONSE_REASON_INVALID,
        RESPONSE_QUESTIONS_INVALID,
        RESPONSE_EVIDENCE_INVALID,
        MODEL_REPORTED_ERROR,
        ACTION_CONTRACT_REJECTED,
        RESULT_PROCESSING_FAILED
    }

    /** Optional fixed-code observer for rejection/repair events; never includes model content. */
    protected void onDiagnostic(DiagnosticCode code) {
    }

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
            return processingError(DiagnosticCode.INPUT_REJECTED);
        }

        PreferenceEntity safePreference = preference == null ? new PreferenceEntity() : preference;
        DiagnosticCode failureStage = DiagnosticCode.PAYLOAD_REJECTED;
        try {
            String userPayload = buildUserPayload(
                    safeQuestion,
                    limit(jobContext, MAX_JOB_CONTEXT_LENGTH),
                    limit(resumeContent, MAX_RESUME_LENGTH),
                    limit(serializePreference(safePreference), MAX_PREFERENCE_LENGTH));
            if (StringUtils.isBlank(userPayload)) {
                return processingError(DiagnosticCode.PAYLOAD_REJECTED);
            }
            ParsedDecision decision;
            for (int attempt = 0; ; attempt++) {
                if (attempt != 0 && Thread.currentThread().isInterrupted()) {
                    throw new InvalidModelResponse(DiagnosticCode.RESPONSE_JSON_INVALID);
                }
                failureStage = DiagnosticCode.MODEL_CALL_FAILED;
                String modelResponse = aiServiceFacade.askAndAnswer(
                        AIPromptStrConstant.AI_MESSAGE_TRIAGE_PROMPT, userPayload);
                failureStage = DiagnosticCode.RESULT_PROCESSING_FAILED;
                try {
                    decision = parseResponse(modelResponse);
                    break;
                } catch (InvalidModelResponse rejected) {
                    if (rejected.code != DiagnosticCode.RESPONSE_JSON_INVALID
                            || attempt != 0 || Thread.currentThread().isInterrupted()) {
                        throw rejected;
                    }
                    // One repair uses the identical trusted prompt and original payload, never model text.
                    emitDiagnostic(DiagnosticCode.RESPONSE_JSON_INVALID_REPAIR_REQUESTED);
                }
            }
            if (decision.category() == Category.PROCESSING_ERROR) {
                return processingError(DiagnosticCode.MODEL_REPORTED_ERROR);
            }

            Action action = resolveAction(
                    decision.category(), decision.action(), safePreference.isMarketingReplyE());
            if (action == null) {
                return processingError(DiagnosticCode.ACTION_CONTRACT_REJECTED);
            }
            return MessageTriageResultVO.builder()
                    .category(decision.category())
                    .action(action)
                    .reasonCode(decision.reasonCode())
                    .plainLanguageReason(sanitizeOutput(decision.reason()))
                    .clarificationQuestions(sanitizeOutput(decision.questions()))
                    .evidence(sanitizeOutput(decision.evidence()))
                    .build();
        } catch (InvalidModelResponse rejected) {
            return processingError(rejected.code);
        } catch (Exception ignored) {
            return processingError(failureStage);
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
        JsonNode json = completeJsonObject(response);
        if (json.has("@type")) {
            throw new InvalidModelResponse(DiagnosticCode.RESPONSE_FIELDS_INVALID);
        }
        var fields = json.fieldNames();
        while (fields.hasNext()) {
            if (!ALLOWED_RESPONSE_FIELDS.contains(fields.next())) {
                throw new InvalidModelResponse(DiagnosticCode.RESPONSE_FIELDS_INVALID);
            }
        }

        Category category = enumField(json, "category", Category.class);
        Action action = enumField(json, "action", Action.class);
        String reasonCode = textField(json, "reasonCode", MAX_REASON_CODE_LENGTH);
        String reason = textField(json, "plainLanguageReason", MAX_REASON_LENGTH);
        List<String> questions = stringList(json.get("clarificationQuestions"),
                MAX_QUESTION_COUNT, MAX_QUESTION_LENGTH);
        List<String> evidence = stringList(json.get("evidence"), MAX_EVIDENCE_COUNT, MAX_EVIDENCE_LENGTH);
        if (category == null) {
            throw new InvalidModelResponse(DiagnosticCode.RESPONSE_CATEGORY_INVALID);
        }
        if (action == null) {
            throw new InvalidModelResponse(DiagnosticCode.RESPONSE_ACTION_INVALID);
        }
        if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()) {
            throw new InvalidModelResponse(DiagnosticCode.RESPONSE_REASON_CODE_INVALID);
        }
        if (reason == null) {
            throw new InvalidModelResponse(DiagnosticCode.RESPONSE_REASON_INVALID);
        }
        if (questions == null) {
            throw new InvalidModelResponse(DiagnosticCode.RESPONSE_QUESTIONS_INVALID);
        }
        if (evidence == null) {
            throw new InvalidModelResponse(DiagnosticCode.RESPONSE_EVIDENCE_INVALID);
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

    private static JsonNode completeJsonObject(String response) {
        if (StringUtils.isBlank(response)) {
            throw new InvalidModelResponse(DiagnosticCode.RESPONSE_EMPTY);
        }
        if (response.length() > MAX_MODEL_RESPONSE_LENGTH) {
            throw new InvalidModelResponse(DiagnosticCode.RESPONSE_TOO_LARGE);
        }
        String text = response.trim();
        if (text.startsWith("```")) {
            int lineEnd = text.indexOf('\n');
            if (lineEnd < 0 || !text.endsWith("```")) {
                throw new InvalidModelResponse(DiagnosticCode.RESPONSE_JSON_INVALID);
            }
            String fence = text.substring(0, lineEnd).trim();
            if (!"```".equals(fence) && !"```json".equalsIgnoreCase(fence)) {
                throw new InvalidModelResponse(DiagnosticCode.RESPONSE_JSON_INVALID);
            }
            text = text.substring(lineEnd + 1, text.length() - 3).trim();
        }
        try {
            JsonNode root = DECISION_JSON.readTree(text);
            if (root == null || !root.isObject()) {
                throw new InvalidModelResponse(DiagnosticCode.RESPONSE_JSON_INVALID);
            }
            return root;
        } catch (Exception ignored) {
            throw new InvalidModelResponse(DiagnosticCode.RESPONSE_JSON_INVALID);
        }
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
        values.put("structuredPreference", StructuredPreferenceText.forReply(preference, false));
        try {
            String json = SAFE_JSON.writeValueAsString(values);
            // 偏好写得很长时，逐步截短每个文字字段，而不是让整条消息分诊失败。
            int fieldCap = MAX_PREFERENCE_LENGTH;
            while (json.length() > MAX_PREFERENCE_LENGTH && fieldCap > 50) {
                fieldCap = fieldCap / 2;
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    if (entry.getValue() instanceof String text && text.length() > fieldCap) {
                        entry.setValue(text.substring(0, fieldCap));
                    }
                }
                json = SAFE_JSON.writeValueAsString(values);
            }
            return json;
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

    private MessageTriageResultVO processingError(DiagnosticCode code) {
        emitDiagnostic(code);
        return result(Category.PROCESSING_ERROR, Action.NO_ACTION_RETRY,
                "AI_PROCESSING_ERROR", "消息暂时无法安全分析，请稍后重试。");
    }

    private void emitDiagnostic(DiagnosticCode code) {
        try {
            onDiagnostic(code);
        } catch (RuntimeException ignored) {
            // Observability must not change the bounded repair or fail-closed result.
        }
    }

    private static final class InvalidModelResponse extends RuntimeException {
        private final DiagnosticCode code;

        private InvalidModelResponse(DiagnosticCode code) {
            super(code.name());
            this.code = code;
        }
    }

    private record ParsedDecision(Category category,
                                  Action action,
                                  String reasonCode,
                                  String reason,
                                  List<String> questions,
                                  List<String> evidence) {
    }
}
