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
            diagnostics.clear();
            MessageTriageResultVO result = classify();
            assertFailureUnchanged(result);
            assertEquals(List.of(DiagnosticCode.valueOf(rejection.code())), diagnostics, rejection.code());
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
    void diagnosticObserverCannotChangeTheFailClosedResult() {
        MessageTriageService throwingObserver = new MessageTriageService() {
            @Override
            protected void onDiagnostic(DiagnosticCode code) {
                throw new IllegalStateException("synthetic observer failure");
            }
        };
        assertFailureUnchanged(throwingObserver.classify(" ", null, "", new PreferenceEntity(), 1));
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
        private RuntimeException failure;
        private int calls;

        @Override
        public String askAndAnswer(String systemPrompt, String userPrompt) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
