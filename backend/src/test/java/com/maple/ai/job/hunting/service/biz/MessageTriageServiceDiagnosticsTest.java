package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Action;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Category;
import com.maple.ai.job.hunting.service.ai.AIServiceFacade;
import com.maple.ai.job.hunting.service.biz.MessageTriageService.DiagnosticCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageTriageServiceDiagnosticsTest {
    private static final String VALID = """
            {"category":"NORMAL_JOB","action":"REPLY","reasonCode":"SYNTHETIC_ONLY",
             "plainLanguageReason":"Synthetic contract check.","clarificationQuestions":[],"evidence":[]}
            """;

    private final List<DiagnosticCode> diagnostics = new ArrayList<>();
    private final SyntheticFacade ai = new SyntheticFacade();
    private final MessageTriageService service = new MessageTriageService() {
        @Override
        protected void onDiagnostic(DiagnosticCode code) {
            diagnostics.add(code);
        }
    };

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "aiServiceFacade", ai);
    }

    @Test
    void distinguishesStrictResponseRejectionsWithoutChangingFailureResults() {
        record Rejection(String response, String code) {}
        List<Rejection> cases = List.of(
                new Rejection("", "RESPONSE_EMPTY"),
                new Rejection("x".repeat(8001), "RESPONSE_TOO_LARGE"),
                new Rejection("synthetic non-json", "RESPONSE_JSON_INVALID"),
                new Rejection(VALID.replace("\"category\":\"NORMAL_JOB\"",
                        "\"category\":\"NORMAL_JOB\",\"category\":\"HIGH_RISK\""), "RESPONSE_JSON_INVALID"),
                new Rejection(VALID + VALID, "RESPONSE_JSON_INVALID"),
                new Rejection(VALID.replace("\"category\":\"NORMAL_JOB\"",
                        "\"extra\":\"synthetic-only\",\"category\":\"NORMAL_JOB\""), "RESPONSE_FIELDS_INVALID"),
                new Rejection(VALID.replace("NORMAL_JOB", "UNKNOWN"), "RESPONSE_CATEGORY_INVALID"),
                new Rejection(VALID.replace("\"action\":\"REPLY\"", "\"action\":\"UNKNOWN\""),
                        "RESPONSE_ACTION_INVALID"),
                new Rejection(VALID.replace("SYNTHETIC_ONLY", "invalid code"), "RESPONSE_REASON_CODE_INVALID"),
                new Rejection(VALID.replace("Synthetic contract check.", "x".repeat(241)),
                        "RESPONSE_REASON_INVALID"),
                new Rejection(VALID.replace("\"clarificationQuestions\":[]", "\"clarificationQuestions\":null"),
                        "RESPONSE_QUESTIONS_INVALID"),
                new Rejection(VALID.replace("\"evidence\":[]", "\"evidence\":[\"" + "x".repeat(241) + "\"]"),
                        "RESPONSE_EVIDENCE_INVALID"),
                new Rejection(VALID.replace("NORMAL_JOB", "PROCESSING_ERROR"), "MODEL_REPORTED_ERROR"),
                new Rejection(VALID.replace("NORMAL_JOB", "UNCERTAIN").replace("\"action\":\"REPLY\"",
                        "\"action\":\"NEEDS_USER_CONFIRMATION\""), "ACTION_CONTRACT_REJECTED"));

        for (Rejection rejection : cases) {
            ai.response = rejection.response();
            ai.calls = 0;
            diagnostics.clear();
            MessageTriageResultVO result = classify();
            assertFailureUnchanged(result);
            boolean repair = rejection.code().equals("RESPONSE_JSON_INVALID");
            assertEquals(repair ? List.of("RESPONSE_JSON_INVALID_REPAIR_REQUESTED", rejection.code())
                            : List.of(rejection.code()),
                    diagnostics.stream().map(Enum::name).toList(), rejection.code());
            assertEquals(repair ? 2 : 1, ai.calls, rejection.code());
        }
    }

    @Test
    void repairsMalformedJsonOnceUsingOnlyTheOriginalTrustedPromptAndPayload() {
        ai.responses = List.of("MODEL_OUTPUT_NEVER_TRUST{", VALID);

        MessageTriageResultVO result = classify();

        assertEquals(Category.NORMAL_JOB, result.getCategory());
        assertEquals(Action.REPLY, result.getAction());
        assertEquals(2, ai.calls);
        assertEquals(ai.prompts.get(0), ai.prompts.get(1));
        assertFalse(ai.prompts.get(1).toString().contains("MODEL_OUTPUT_NEVER_TRUST"));
        assertEquals(List.of("RESPONSE_JSON_INVALID_REPAIR_REQUESTED"),
                diagnostics.stream().map(Enum::name).toList());
    }

    @Test
    void twoMalformedResponsesFailClosedWithoutAThirdRequest() {
        ai.responses = List.of("first synthetic invalid response", "second synthetic invalid response");

        assertFailureUnchanged(classify());

        assertEquals(2, ai.calls);
        assertEquals(List.of("RESPONSE_JSON_INVALID_REPAIR_REQUESTED", "RESPONSE_JSON_INVALID"),
                diagnostics.stream().map(Enum::name).toList());
    }

    @Test
    void secondResponseMustPassTheSameEnumAndActionGuards() {
        for (String invalid : List.of(
                VALID.replace("NORMAL_JOB", "UNKNOWN"),
                VALID.replace("NORMAL_JOB", "UNCERTAIN")
                        .replace("\"action\":\"REPLY\"", "\"action\":\"NEEDS_USER_CONFIRMATION\""))) {
            ai.responses = List.of("synthetic invalid json", invalid);
            ai.calls = 0;
            diagnostics.clear();

            assertFailureUnchanged(classify());
            assertEquals(2, ai.calls);
            assertEquals("RESPONSE_JSON_INVALID_REPAIR_REQUESTED", diagnostics.get(0).name());
            assertEquals(invalid.contains("UNKNOWN") ? "RESPONSE_CATEGORY_INVALID" : "ACTION_CONTRACT_REJECTED",
                    diagnostics.get(1).name());
        }
    }

    @Test
    void validUncertainDecisionIsNeverRetriedToSeekADifferentCategory() {
        ai.responses = List.of(VALID.replace("NORMAL_JOB", "UNCERTAIN"));

        MessageTriageResultVO result = classify();

        assertEquals(Category.UNCERTAIN, result.getCategory());
        assertEquals(Action.REPLY, result.getAction());
        assertEquals(1, ai.calls);
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void providerFailureOnTheRepairAttemptIsNotRetried() {
        ai.responses = List.of("synthetic invalid json", VALID);
        ai.failure = new IllegalStateException("synthetic provider error; never disclose");
        ai.failureOnCall = 2;

        assertFailureUnchanged(classify());

        assertEquals(2, ai.calls);
        assertEquals(List.of("RESPONSE_JSON_INVALID_REPAIR_REQUESTED", "MODEL_CALL_FAILED"),
                diagnostics.stream().map(Enum::name).toList());
    }

    @Test
    void interruptedCallerDoesNotStartARepairRequest() {
        ai.response = "synthetic invalid json";
        Thread.currentThread().interrupt();
        try {
            assertFailureUnchanged(classify());
            assertEquals(1, ai.calls);
            assertEquals(List.of(DiagnosticCode.RESPONSE_JSON_INVALID), diagnostics);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationAfterTheRepairEventPreventsTheSecondCall() {
        ai.responses = List.of("synthetic invalid json", VALID);
        MessageTriageService cancelling = new MessageTriageService() {
            @Override
            protected void onDiagnostic(DiagnosticCode code) {
                if (code == DiagnosticCode.RESPONSE_JSON_INVALID_REPAIR_REQUESTED) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ReflectionTestUtils.setField(cancelling, "aiServiceFacade", ai);
        try {
            assertFailureUnchanged(cancelling.classify("Synthetic job", null, "", new PreferenceEntity(), 1));
            assertEquals(1, ai.calls);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void modelExceptionsHaveOnlyAFixedCodeAndNeverExposeTheirMessages() {
        ai.failure = new IllegalStateException("synthetic provider body; never disclose");
        MessageTriageResultVO result = classify();
        assertFailureUnchanged(result);
        assertEquals(List.of(DiagnosticCode.MODEL_CALL_FAILED), diagnostics);
        assertFalse(result.toString().contains("synthetic provider body"));
    }

    @Test
    void inputAndPayloadFailuresAreDistinguishedBeforeAnyModelCall() {
        MessageTriageResultVO empty = service.classify(" ", null, "", new PreferenceEntity(), 1);
        assertFailureUnchanged(empty);
        assertEquals(List.of(DiagnosticCode.INPUT_REJECTED), diagnostics);
        diagnostics.clear();
        PreferenceEntity oversized = new PreferenceEntity();
        oversized.setJobGoal("x".repeat(5000));
        MessageTriageResultVO payload = service.classify("Synthetic job", null, "", oversized, 1);
        assertFailureUnchanged(payload);
        assertEquals(List.of(DiagnosticCode.PAYLOAD_REJECTED), diagnostics);
        assertEquals(0, ai.calls);
    }

    @Test
    void validDecisionsAndVerifiedProtocolEventsHaveNoFailureDiagnostic() {
        ai.response = VALID;
        assertEquals(Category.NORMAL_JOB, classify().getCategory());
        assertEquals(Category.PLATFORM_EVENT,
                service.classify("Synthetic event", null, "", new PreferenceEntity(), 4).getCategory());
        assertTrue(diagnostics.isEmpty());
        assertEquals(1, ai.calls);
    }

    @Test
    void diagnosticObserverCannotChangeTheFailClosedOrRepairedResult() {
        MessageTriageService throwingObserver = new MessageTriageService() {
            @Override
            protected void onDiagnostic(DiagnosticCode code) {
                throw new IllegalStateException("synthetic observer failure");
            }
        };
        assertFailureUnchanged(throwingObserver.classify(" ", null, "", new PreferenceEntity(), 1));
        ai.responses = List.of("synthetic invalid json", VALID);
        ReflectionTestUtils.setField(throwingObserver, "aiServiceFacade", ai);
        assertEquals(Category.NORMAL_JOB,
                throwingObserver.classify("Synthetic job", null, "", new PreferenceEntity(), 1).getCategory());
        assertEquals(2, ai.calls);
    }

    private MessageTriageResultVO classify() {
        return service.classify("Synthetic job", null, "", new PreferenceEntity(), 1);
    }

    private static void assertFailureUnchanged(MessageTriageResultVO result) {
        assertEquals(Category.PROCESSING_ERROR, result.getCategory());
        assertEquals(Action.NO_ACTION_RETRY, result.getAction());
        assertEquals("AI_PROCESSING_ERROR", result.getReasonCode());
    }

    private static final class SyntheticFacade extends AIServiceFacade {
        private String response;
        private List<String> responses;
        private RuntimeException failure;
        private int failureOnCall;
        private int calls;
        private final List<List<String>> prompts = new ArrayList<>();

        @Override
        public String askAndAnswer(String systemPrompt, String userPrompt) {
            calls++;
            prompts.add(List.of(systemPrompt, userPrompt));
            if (failure != null && (failureOnCall == 0 || calls == failureOnCall)) {
                throw failure;
            }
            if (responses != null) {
                assertTrue(calls <= responses.size(), "Unexpected additional model request");
                return responses.get(calls - 1);
            }
            return response;
        }
    }
}
