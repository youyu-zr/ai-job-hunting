package com.maple.ai.job.hunting.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maple.ai.job.hunting.consts.AIPromptStrConstant;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ControlledSemanticEvalTest {

    private static Properties properties(String... entries) {
        Properties result = new Properties();
        for (int i = 0; i < entries.length; i += 2) {
            result.setProperty(entries[i], entries[i + 1]);
        }
        return result;
    }

    private static Properties primary() {
        return properties(
                "spring.ai.synthetic.base-url", "${EVAL_URL:https://example.org/gateway}",
                "spring.ai.synthetic.api-key", "${EVAL_KEY}",
                "spring.ai.synthetic.chat.options.model", "${EVAL_MODEL:synthetic-model-v1}",
                "spring.ai.synthetic.chat.options.first", "true");
    }

    @Test
    void resolvesOnlyExplicitPrimaryAndPassedEnvironmentWithDefaults() {
        EvalProviderConfig config = EvalProviderConfig.resolve(primary(), Map.of("EVAL_KEY", "synthetic-only"));
        assertEquals("synthetic", config.name());
        assertEquals("synthetic-model-v1", config.model());
        assertEquals("example.org", config.host());
        assertEquals("DEFAULT_POOL_FILE_MAPPING_ABSENT", config.askCompatibility());
        assertFalse(config.toString().contains("synthetic-only"));
        assertFalse(config.toString().contains("/gateway"));
    }

    @Test
    void sceneMappingMustExplicitlyMatchPoolAndNeverClaimsDatabaseOverridesAreKnown() {
        Properties p = primary();
        p.setProperty("use-ai-map", "{\"ask\":\"openai-pool\",\"session\":\"other\"}");
        EvalProviderConfig compatible = EvalProviderConfig.resolve(p, Map.of("EVAL_KEY", "synthetic-only"));
        assertTrue(compatible.askCompatible());
        assertEquals("FILE_ASK_OPENAI_POOL_COMPATIBLE", compatible.askCompatibility());
        for (String mapping : new String[]{"{\"ask\":\"openai\"}", "{\"ask\":\"custom\"}", "{}"}) {
            p.setProperty("use-ai-map", mapping);
            EvalProviderConfig incompatible = EvalProviderConfig.resolve(p, Map.of("EVAL_KEY", "synthetic-only"));
            assertFalse(incompatible.askCompatible());
            assertEquals("FILE_ASK_ROUTE_INCOMPATIBLE", incompatible.askCompatibility());
        }
    }

    @Test
    void explicitStructuredPoolWinsOverAutoDiscoveryAndSupportsAliases() {
        Properties p = primary();
        p.setProperty("openai.pool.config.list", """
                [{"name":"json-primary","base-url":"${URL}","api-key":"${KEY}",
                  "chat-options-model":"json-model","chat-options-first":true}]
                """);
        EvalProviderConfig config = EvalProviderConfig.resolve(p,
                Map.of("URL", "https://example.org", "KEY", "synthetic-only"));
        assertEquals("json-primary", config.name());
        assertEquals("json-model", config.model());
    }

    @Test
    void ambiguousMissingUnresolvedProxyAndUnsafeEndpointsFailClosedWithoutEchoingValues() {
        Properties missing = primary();
        missing.remove("spring.ai.synthetic.chat.options.first");
        Properties ambiguous = primary();
        ambiguous.setProperty("spring.ai.other.chat.options.first", "true");
        Properties proxy = primary();
        proxy.setProperty("spring.ai.synthetic.chat.options.proxy", "true");
        Properties pathEscape = primary();
        pathEscape.setProperty("spring.ai.synthetic.completions-path", "https://other.example/steal");
        Properties unsafe = primary();
        unsafe.setProperty("spring.ai.synthetic.base-url", "https://user:synthetic-only@example.org/a?key=x");
        for (Properties p : new Properties[]{missing, ambiguous, proxy, pathEscape, unsafe}) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> EvalProviderConfig.resolve(p, Map.of("EVAL_KEY", "synthetic-only")));
            assertEquals("EVAL_CONFIGURATION_REJECTED", failure.getMessage());
        }
        assertThrows(RuntimeException.class, () -> EvalProviderConfig.resolve(primary(), Map.of()));
    }

    @Test
    void rejectsDuplicateJsonKeysCyclicPlaceholdersAndDisabledAutoDiscovery() {
        Properties duplicate = properties("openai.pool.config.list",
                "[{\"name\":\"a\",\"name\":\"b\",\"chat.options.first\":true}]");
        assertThrows(RuntimeException.class, () -> EvalProviderConfig.resolve(duplicate, Map.of()));
        Properties cycle = primary();
        cycle.setProperty("spring.ai.synthetic.api-key", "${LOOP}");
        assertThrows(RuntimeException.class,
                () -> EvalProviderConfig.resolve(cycle, Map.of("LOOP", "${LOOP}")));
        Properties disabled = primary();
        disabled.setProperty("openai.pool.config.auto", "false");
        assertThrows(RuntimeException.class,
                () -> EvalProviderConfig.resolve(disabled, Map.of("EVAL_KEY", "synthetic-only")));
    }

    @Test
    void offlineExercisesProductionParserAndGuardsWithoutClaimingSemanticResults() throws Exception {
        assertEquals(47, ControlledSemanticEval.fixtures().size());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = ControlledSemanticEval.run(new String[]{"--offline"},
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertEquals(0, exit, bytes.toString(StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        JsonNode summary = null;
        for (String line : output.lines().toList()) {
            JsonNode record = new ObjectMapper().readTree(line);
            if (record.has("counts")) {
                summary = record.path("counts");
            } else {
                assertTrue(record.has("caseId"));
                assertFalse(record.has("message"));
                assertFalse(record.has("reason"));
            }
        }
        assertNotNull(summary);
        assertTrue(summary.path("semanticSkipped").asInt() >= 20);
        assertTrue(summary.path("offlineChecked").asInt() >= 12);
        assertTrue(summary.path("offlineRequests").asInt() >= 6);
        assertEquals(0, summary.path("liveRequests").asInt());
        assertEquals(0, summary.path("failed").asInt());
        assertFalse(output.contains("synthetic-only"));
    }

    @Test
    void offlineFacadeSuppliesSyntheticParserResponseWithoutHttpClientConstruction() throws Exception {
        ControlledSemanticEval.Fixture fixture = ControlledSemanticEval.fixtures().stream()
                .filter(f -> f.id.equals("parser_normal")).findFirst().orElseThrow();
        ControlledSemanticEval.OfflineApi api = new ControlledSemanticEval.OfflineApi(fixture);
        String response = api.askAndAnswer("Synthetic system rule", "Synthetic user data",
                "OFFLINE_CONTRACT_ONLY");
        assertEquals("NORMAL_JOB", new ObjectMapper().readTree(response).path("category").asText());
        assertEquals(1, api.requests.get());
    }

    @Test
    void productionTriageSuppliesFixedRulesAndUserPayloadToSyntheticFacade() throws Exception {
        ControlledSemanticEval.Fixture fixture = ControlledSemanticEval.fixtures().stream()
                .filter(f -> f.id.equals("parser_normal")).findFirst().orElseThrow();
        ControlledSemanticEval.OfflineApi response = new ControlledSemanticEval.OfflineApi(fixture);
        AtomicReference<String[]> captured = new AtomicReference<>();
        ControlledSemanticEval.EvalApi api = new ControlledSemanticEval.EvalApi() {
            @Override
            String askAndAnswer(String systemPrompt, String userPrompt, String model) {
                captured.set(new String[]{systemPrompt, userPrompt});
                return response.askAndAnswer(systemPrompt, userPrompt, model);
            }
        };
        ControlledSemanticEval.Outcome outcome =
                ControlledSemanticEval.classify(fixture, api, "OFFLINE_CONTRACT_ONLY");
        assertEquals("NORMAL_JOB", outcome.category());
        assertEquals("OFFLINE_CONTRACT_ONLY", outcome.model());
        assertNotNull(captured.get());
        assertEquals(AIPromptStrConstant.AI_MESSAGE_TRIAGE_PROMPT, captured.get()[0]);
        JsonNode payload = new ObjectMapper().readTree(captured.get()[1]);
        assertEquals(fixture.message, payload.path("question").asText());
        assertEquals("\u672a\u63d0\u4f9b", payload.path("resumeContent").asText());
        assertEquals(1, response.requests.get());
    }

    @Test
    void semanticPromotionFixturesPreserveOptInAndPolicyAtTheParserBoundary() throws Exception {
        for (String id : new String[]{"promotion_opt_in", "promotion_policy_silent", "funnel_course"}) {
            ControlledSemanticEval.Fixture fixture = ControlledSemanticEval.fixtures().stream()
                    .filter(f -> f.id.equals(id)).findFirst().orElseThrow();
            ControlledSemanticEval.EvalApi synthetic = new ControlledSemanticEval.EvalApi() {
                @Override
                String askAndAnswer(String systemPrompt, String userPrompt, String model) {
                    try {
                        JsonNode preference = new ObjectMapper().readTree(userPrompt).path("preference");
                        assertEquals(!id.equals("funnel_course"), preference.path("marketingReplyE").asBoolean());
                        if (fixture.preference != null) {
                            assertEquals(fixture.preference.path("promotionReplyPolicy").asText(),
                                    preference.path("promotionReplyPolicy").asText());
                        }
                        String action = id.equals("promotion_policy_silent") ? "SILENT_CURRENT_MESSAGE" : "REPLY";
                        return "{\"category\":\"PROMOTION\",\"action\":\"" + action
                                + "\",\"reasonCode\":\"SYNTHETIC_ONLY\",\"plainLanguageReason\":\"Contract only.\","
                                + "\"clarificationQuestions\":[],\"evidence\":[]}";
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("SYNTHETIC_PAYLOAD_INVALID");
                    }
                }
            };
            ControlledSemanticEval.Outcome outcome =
                    ControlledSemanticEval.classify(fixture, synthetic, "OFFLINE_CONTRACT_ONLY");
            assertEquals(fixture.expectedCategory.name(), outcome.category(), id);
            assertEquals(fixture.expectedAction.name(), outcome.action(), id);
        }
    }

    @Test
    void productionProtocolAndParserPathsMatchEveryOfflineFixtureWithoutNetwork() throws Exception {
        for (ControlledSemanticEval.Fixture fixture : ControlledSemanticEval.fixtures()) {
            if (fixture.kind.equals("SEMANTIC")) {
                continue;
            }
            ControlledSemanticEval.OfflineApi api = new ControlledSemanticEval.OfflineApi(fixture);
            ControlledSemanticEval.Outcome outcome =
                    ControlledSemanticEval.classify(fixture, api, "OFFLINE_CONTRACT_ONLY");
            assertEquals(fixture.expectedCategory.name(), outcome.category(), fixture.id);
            assertEquals(fixture.expectedAction.name(), outcome.action(), fixture.id);
            assertEquals(fixture.kind.equals("PROTOCOL") || fixture.kind.equals("INPUT_GUARD") ? 0
                            : fixture.id.equals("parser_malformed") || fixture.id.equals("parser_duplicate") ? 2 : 1,
                    api.requests.get(), fixture.id);
        }
    }

    @Test
    void repairedFormatFailureRemainsVisibleAndCountsBothRequests() throws Exception {
        ControlledSemanticEval.Fixture fixture = ControlledSemanticEval.fixtures().stream()
                .filter(f -> f.id.equals("parser_normal")).findFirst().orElseThrow();
        ControlledSemanticEval.OfflineApi syntheticResponse = new ControlledSemanticEval.OfflineApi(fixture);
        ControlledSemanticEval.EvalApi api = new ControlledSemanticEval.EvalApi() {
            @Override
            String askAndAnswer(String systemPrompt, String userPrompt, String model) {
                int attempt = requests.incrementAndGet();
                assertTrue(attempt <= 2);
                return attempt == 1 ? "synthetic invalid json"
                        : syntheticResponse.askAndAnswer(systemPrompt, userPrompt, model);
            }
        };

        ControlledSemanticEval.Outcome outcome =
                ControlledSemanticEval.classify(fixture, api, "OFFLINE_CONTRACT_ONLY");

        assertEquals("NORMAL_JOB", outcome.category());
        assertEquals("REPLY", outcome.action());
        assertEquals(2, api.requests.get());
        JsonNode record = new ObjectMapper().valueToTree(outcome);
        assertEquals("RESPONSE_JSON_INVALID", record.path("firstDiagnosticCode").asText());
        assertEquals("NONE", record.path("diagnosticCode").asText());
        assertTrue(record.path("repairAttempted").asBoolean());
    }

    @Test
    void repairHistorySurvivesTimeoutAndUnexpectedExecutionFailure() {
        var trace = java.util.List.of(com.maple.ai.job.hunting.service.biz.MessageTriageService
                .DiagnosticCode.RESPONSE_JSON_INVALID_REPAIR_REQUESTED);
        for (ControlledSemanticEval.Outcome outcome : java.util.List.of(
                ControlledSemanticEval.Outcome.timeout("SYNTHETIC_ONLY", trace),
                ControlledSemanticEval.Outcome.failure("SYNTHETIC_ONLY", trace))) {
            assertEquals("PROCESSING_ERROR", outcome.category());
            assertEquals("NO_ACTION_RETRY", outcome.action());
            assertEquals("RESPONSE_JSON_INVALID", outcome.firstDiagnosticCode());
            assertTrue(outcome.repairAttempted());
            assertTrue(java.util.Set.of("REQUEST_TIMEOUT", "EVAL_EXECUTION_FAILED")
                    .contains(outcome.diagnosticCode()));
        }
    }

    @Test
    void offlineReportContainsOnlyFixedDiagnosticCodesAndKeepsSemanticCasesUnevaluated() throws Exception {
        Map<String, String> failures = Map.of(
                "guard_empty", "INPUT_REJECTED",
                "guard_oversized_risk_tail", "INPUT_REJECTED",
                "parser_uncertain_confirmation", "ACTION_CONTRACT_REJECTED",
                "parser_malformed", "RESPONSE_JSON_INVALID",
                "parser_duplicate", "RESPONSE_JSON_INVALID",
                "parser_unknown", "RESPONSE_CATEGORY_INVALID",
                "parser_extra", "RESPONSE_FIELDS_INVALID",
                "parser_oversized", "RESPONSE_TOO_LARGE",
                "transport_failure", "MODEL_CALL_FAILED");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertEquals(0, ControlledSemanticEval.run(new String[]{"--offline"}, new PrintStream(bytes)));
        for (String line : bytes.toString(StandardCharsets.UTF_8).lines().toList()) {
            JsonNode record = new ObjectMapper().readTree(line);
            if (record.has("counts")) {
                assertEquals(16, record.path("counts").path("offlineChecked").asInt());
                assertEquals(31, record.path("counts").path("semanticSkipped").asInt());
                assertEquals(0, record.path("counts").path("liveRequests").asInt());
                assertEquals(12, record.path("counts").path("offlineRequests").asInt());
                assertEquals(0, record.path("counts").path("semanticRepairAttempts").asInt());
                assertEquals(0, record.path("counts").path("semanticFirstAttemptFailed").asInt());
            } else {
                assertEquals(failures.getOrDefault(record.path("caseId").asText(), "NONE"),
                        record.path("diagnosticCode").asText(), record.path("caseId").asText());
                assertEquals(record.path("diagnosticCode").asText(), record.path("firstDiagnosticCode").asText());
                assertEquals(java.util.Set.of("parser_malformed", "parser_duplicate")
                        .contains(record.path("caseId").asText()), record.path("repairAttempted").asBoolean());
                assertFalse(record.has("response"));
                assertFalse(record.has("reason"));
                assertFalse(record.has("exception"));
            }
        }
    }

    @Test
    void flagsRejectNetworkOptionsInOfflineAndNeverEchoArgumentsOrPaths() {
        for (String[] args : new String[][]{
                {},
                {"--evaluate"},
                {"--offline", "--properties", "synthetic-private-path"},
                {"--offline", "--concurrency", "4"},
                {"--evaluate", "--offline"},
                {"--api-key", "synthetic-only"},
                {"--offline", "--fixtures", "synthetic-private-path"}
        }) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int exit = ControlledSemanticEval.run(args, new PrintStream(bytes));
            assertEquals(2, exit);
            assertEquals("{\"counts\":{\"configurationRejected\":1}}\n",
                    bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
    }
}
