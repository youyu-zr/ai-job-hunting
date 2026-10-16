package com.maple.ai.job.hunting.eval;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.maple.ai.job.hunting.common.ai.AIConfigHelper;
import com.maple.ai.job.hunting.common.ai.CompletionPathOpenAiApi;
import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Action;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Category;
import com.maple.ai.job.hunting.service.ai.AIServiceFacade;
import com.maple.ai.job.hunting.service.ai.impl.OpenAIService;
import com.maple.ai.job.hunting.service.biz.MessageTriageService;
import com.maple.ai.job.hunting.service.biz.MessageTriageService.DiagnosticCode;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletion;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.maple.ai.job.hunting.eval.EvalProviderConfig.JSON;
import static com.maple.ai.job.hunting.eval.EvalProviderConfig.require;

/**
 * Standalone classifier-only entry point. See the adjacent resources/eval/README.md.
 * Never instantiate an application context or call init(), sessionChat(), or a mapper.
 */
public final class ControlledSemanticEval {
    private static final long REQUEST_SECONDS = 60;
    private static final String RESOURCE = "/eval/task8-synthetic-hr.json";
    private static final String UNREPORTED = "UNREPORTED";

    private ControlledSemanticEval() {
    }

    public static void main(String[] args) {
        PrintStream report = System.out;
        // Third-party diagnostics and error bodies must never join the allowlisted report.
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        int exit;
        try {
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
                context.reset();
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
            }
            java.util.logging.LogManager.getLogManager().reset();
            exit = run(args, report);
        } catch (Throwable ignored) {
            report.println("{\"counts\":{\"harnessFailure\":1}}");
            exit = 2;
        }
        report.flush();
        System.exit(exit);
    }

    static int run(String[] args, PrintStream report) {
        try {
            Options options = Options.parse(args);
            EvalProviderConfig config = options.mode.equals("--offline") ? null
                    : EvalProviderConfig.read(options.properties, options.envFiles);
            if (options.mode.equals("--describe")) {
                emit(report, Map.of("provider", config.name(), "model", config.model(), "host", config.host(),
                        "askCompatibility", config.askCompatibility(),
                        "runtimeDbOverrides", "UNAVAILABLE_NOT_CHECKED"));
                return config.askCompatible() ? 0 : 2;
            }
            if (config != null) {
                require(config.askCompatible());
                require(Runtime.getRuntime().maxMemory() <= 192L * 1024 * 1024);
            }
            List<Fixture> fixtures = fixtures();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String key : List.of("total", "offlineChecked", "offlineRequests", "semanticChecked",
                    "semanticSkipped", "liveRequests", "passed", "failed", "riskFalsePositives",
                    "riskFalseNegatives", "promotionFalsePositives", "promotionFalseNegatives",
                    "categoryMismatches", "actionMismatches",
                    "processingErrors", "requestTimeouts", "semanticFirstAttemptPassed",
                    "semanticFirstAttemptFailed", "semanticRepairAttempts", "semanticRepairRecovered")) {
                counts.put(key, 0);
            }
            List<Fixture> semantic = new ArrayList<>();
            for (Fixture fixture : fixtures) {
                if (fixture.kind.equals("SEMANTIC")) {
                    semantic.add(fixture);
                } else {
                    OfflineApi api = new OfflineApi(fixture);
                    Outcome outcome = classify(fixture, api, "OFFLINE_CONTRACT_ONLY");
                    add(counts, "offlineRequests", api.requests.get());
                    account(report, fixture, outcome, counts, false);
                }
            }
            if (config == null) {
                for (Fixture fixture : semantic) {
                    account(report, fixture, Outcome.skipped("NOT_EVALUATED"), counts, true);
                }
            } else {
                evaluate(report, semantic, config, options.concurrency, counts);
            }
            emit(report, Map.of("counts", counts));
            return counts.get("failed") == 0
                    && (config == null || counts.get("semanticSkipped") == 0) ? 0 : 1;
        } catch (Exception ignored) {
            report.println("{\"counts\":{\"configurationRejected\":1}}");
            return 2;
        }
    }

    private static void evaluate(PrintStream report, List<Fixture> fixtures, EvalProviderConfig config,
                                 int concurrency, Map<String, Integer> counts) throws Exception {
        LiveApi api = new LiveApi(config);
        ExecutorService workers = Executors.newFixedThreadPool(concurrency, task -> {
            Thread thread = new Thread(task, "controlled-semantic-eval");
            thread.setDaemon(true);
            return thread;
        });
        boolean aborted = false;
        try {
            for (int start = 0; start < fixtures.size(); start += concurrency) {
                int end = Math.min(start + concurrency, fixtures.size());
                if (aborted) {
                    for (int i = start; i < end; i++) {
                        account(report, fixtures.get(i), Outcome.skipped(config.model()), counts, true);
                    }
                    continue;
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REQUEST_SECONDS);
                List<Future<Outcome>> pending = new ArrayList<>();
                List<List<DiagnosticCode>> diagnostics = new ArrayList<>();
                for (int i = start; i < end; i++) {
                    Fixture fixture = fixtures.get(i);
                    List<DiagnosticCode> trace = new CopyOnWriteArrayList<>();
                    diagnostics.add(trace);
                    pending.add(workers.submit(() -> classify(fixture, api, config.model(), trace)));
                }
                for (int i = 0; i < pending.size(); i++) {
                    Future<Outcome> future = pending.get(i);
                    Outcome outcome;
                    try {
                        // Completed requests remain reportable after another request exhausts the wave budget.
                        outcome = future.isDone() ? future.get() : future.get(
                                Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    } catch (java.util.concurrent.TimeoutException ignored) {
                        future.cancel(true);
                        add(counts, "requestTimeouts", 1);
                        outcome = Outcome.timeout(config.model(), diagnostics.get(i));
                        // Never enqueue more calls while a timed-out transport might still be unwinding.
                        aborted = true;
                    } catch (java.util.concurrent.ExecutionException ignored) {
                        outcome = Outcome.failure(config.model(), diagnostics.get(i));
                    }
                    account(report, fixtures.get(start + i), outcome, counts, true);
                }
            }
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(2, TimeUnit.SECONDS);
            counts.put("liveRequests", api.requests.get());
        }
    }

    static Outcome classify(Fixture fixture, EvalApi api, String model) throws Exception {
        return classify(fixture, api, model, new CopyOnWriteArrayList<>());
    }

    private static Outcome classify(Fixture fixture, EvalApi api, String model,
                                    List<DiagnosticCode> diagnostics) throws Exception {
        api.metadata.remove();
        AIServiceFacade routingOnly = new AIServiceFacade() {
            @Override
            public String askAndAnswer(String systemPrompt, String userPrompt) {
                return api.askAndAnswer(systemPrompt, userPrompt, model);
            }

            @Override
            public String askAndAnswer(String prompt) {
                throw new IllegalStateException("ROLE_SEPARATION_REQUIRED");
            }
        };
        MessageTriageService triage = new MessageTriageService() {
            @Override
            protected void onDiagnostic(DiagnosticCode code) {
                diagnostics.add(code);
            }
        };
        Field field = MessageTriageService.class.getDeclaredField("aiServiceFacade");
        field.setAccessible(true);
        field.set(triage, routingOnly);
        PreferenceEntity preference = fixture.preference == null
                ? new PreferenceEntity() : JSON.treeToValue(fixture.preference, PreferenceEntity.class);
        MessageTriageResultVO result = triage.classify(
                fixture.message.repeat(fixture.messageRepeat) + fixture.messageSuffix, fixture.jobContext, "",
                preference, fixture.bodyType);
        String[] metadata = api.metadata.get();
        List<DiagnosticCode> observed = List.copyOf(diagnostics);
        DiagnosticCode last = observed.isEmpty() ? null : observed.get(observed.size() - 1);
        String diagnosticCode = last == null || last == DiagnosticCode.RESPONSE_JSON_INVALID_REPAIR_REQUESTED
                ? result.getCategory() == Category.PROCESSING_ERROR ? "DIAGNOSTIC_UNAVAILABLE" : "NONE"
                : last.name();
        return Outcome.withDiagnostics(result.getCategory().name(), result.getAction().name(),
                metadata == null ? model : metadata[0], metadata == null ? UNREPORTED : metadata[1],
                diagnosticCode, observed);
    }

    private static void account(PrintStream report, Fixture fixture, Outcome result,
                                Map<String, Integer> counts, boolean semantic) throws IOException {
        add(counts, "total", 1);
        boolean skipped = result.category.equals("NOT_EVALUATED");
        if (skipped) {
            add(counts, "semanticSkipped", 1);
        } else {
            add(counts, semantic ? "semanticChecked" : "offlineChecked", 1);
            boolean categoryMatches = fixture.expectedCategory.name().equals(result.category);
            boolean actionMatches = fixture.expectedAction.name().equals(result.action);
            add(counts, categoryMatches && actionMatches ? "passed" : "failed", 1);
            add(counts, "categoryMismatches", categoryMatches ? 0 : 1);
            add(counts, "actionMismatches", actionMatches ? 0 : 1);
            if (semantic) {
                add(counts, !result.repairAttempted && categoryMatches && actionMatches
                        ? "semanticFirstAttemptPassed" : "semanticFirstAttemptFailed", 1);
                add(counts, "semanticRepairAttempts", result.repairAttempted ? 1 : 0);
                add(counts, "semanticRepairRecovered",
                        result.repairAttempted && !result.category.equals("PROCESSING_ERROR") ? 1 : 0);
                add(counts, "processingErrors", result.category.equals("PROCESSING_ERROR") ? 1 : 0);
                add(counts, "riskFalseNegatives",
                        fixture.expectedCategory == Category.HIGH_RISK && !result.category.equals("HIGH_RISK") ? 1 : 0);
                add(counts, "riskFalsePositives",
                        fixture.expectedCategory != Category.HIGH_RISK && result.category.equals("HIGH_RISK") ? 1 : 0);
                add(counts, "promotionFalseNegatives",
                        fixture.expectedCategory == Category.PROMOTION && !result.category.equals("PROMOTION") ? 1 : 0);
                add(counts, "promotionFalsePositives",
                        fixture.expectedCategory != Category.PROMOTION && result.category.equals("PROMOTION") ? 1 : 0);
            }
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("caseId", fixture.id);
        record.put("expectedCategory", fixture.expectedCategory.name());
        record.put("expectedAction", fixture.expectedAction.name());
        record.put("actualCategory", result.category);
        record.put("actualAction", result.action);
        record.put("model", result.model);
        record.put("version", result.version);
        record.put("diagnosticCode", result.diagnosticCode);
        record.put("firstDiagnosticCode", result.firstDiagnosticCode);
        record.put("repairAttempted", result.repairAttempted);
        emit(report, record);
    }

    static List<Fixture> fixtures() throws IOException {
        try (InputStream input = ControlledSemanticEval.class.getResourceAsStream(RESOURCE)) {
            require(input != null);
            byte[] bytes = input.readNBytes(1_048_577);
            require(bytes.length <= 1_048_576);
            JsonNode root = JSON.readTree(bytes);
            require(root.isArray() && root.size() >= 20 && root.size() <= 100);
            List<Fixture> result = new ArrayList<>();
            HashSet<String> ids = new HashSet<>();
            for (JsonNode node : root) {
                Fixture fixture = JSON.treeToValue(node, Fixture.class);
                require(fixture.id != null && fixture.id.matches("[a-z0-9_-]{1,64}") && ids.add(fixture.id));
                require(fixture.name != null && fixture.name.matches(".*\\p{IsHan}.*"));
                require(List.of("SEMANTIC", "PROTOCOL", "INPUT_GUARD", "PARSER", "FAILURE").contains(fixture.kind));
                require(fixture.message != null && fixture.message.length() <= 8000);
                require(fixture.messageRepeat >= 1 && fixture.messageRepeat <= 6000);
                require(fixture.messageSuffix != null
                        && (long) fixture.message.length() * fixture.messageRepeat + fixture.messageSuffix.length() <= 16000);
                require(fixture.offlineResponseRepeat >= 1 && fixture.offlineResponseRepeat <= 9000);
                require(fixture.expectedCategory != null && fixture.expectedAction != null);
                require(fixture.jobContext == null || fixture.jobContext.length() <= 16000);
                result.add(fixture);
            }
            require(result.stream().filter(f -> f.kind.equals("SEMANTIC")).count() >= 20);
            return result;
        }
    }

    private static void emit(PrintStream out, Object value) throws IOException {
        out.println(JSON.writeValueAsString(value));
    }

    private static void add(Map<String, Integer> counts, String key, int value) {
        counts.put(key, counts.get(key) + value);
    }

    public static final class Fixture {
        public String id;
        public String name;
        public String kind;
        public String message;
        public int messageRepeat = 1;
        public String messageSuffix = "";
        public String jobContext;
        public JsonNode preference;
        public Integer bodyType = 1;
        public Category expectedCategory;
        public Action expectedAction;
        public JsonNode offlineResponse;
        public int offlineResponseRepeat = 1;
        public boolean transportFailure;
    }

    record Outcome(String category, String action, String model, String version, String diagnosticCode,
                   String firstDiagnosticCode, boolean repairAttempted) {
        static Outcome skipped(String model) {
            return withDiagnostics("NOT_EVALUATED", "NOT_EVALUATED", model, UNREPORTED, "NONE", List.of());
        }

        static Outcome failure(String model, List<DiagnosticCode> diagnostics) {
            return withDiagnostics("PROCESSING_ERROR", "NO_ACTION_RETRY", model, UNREPORTED,
                    "EVAL_EXECUTION_FAILED", diagnostics);
        }

        static Outcome timeout(String model, List<DiagnosticCode> diagnostics) {
            return withDiagnostics("PROCESSING_ERROR", "NO_ACTION_RETRY", model, UNREPORTED,
                    "REQUEST_TIMEOUT", diagnostics);
        }

        static Outcome withDiagnostics(String category, String action, String model, String version,
                                       String diagnosticCode, List<DiagnosticCode> diagnostics) {
            List<DiagnosticCode> snapshot = List.copyOf(diagnostics);
            boolean repaired = snapshot.contains(DiagnosticCode.RESPONSE_JSON_INVALID_REPAIR_REQUESTED);
            String first = snapshot.isEmpty() ? diagnosticCode
                    : snapshot.get(0) == DiagnosticCode.RESPONSE_JSON_INVALID_REPAIR_REQUESTED
                    ? DiagnosticCode.RESPONSE_JSON_INVALID.name() : snapshot.get(0).name();
            return new Outcome(category, action, model, version, diagnosticCode, first, repaired);
        }
    }

    abstract static class EvalApi {
        final AtomicInteger requests = new AtomicInteger();
        final ThreadLocal<String[]> metadata = new ThreadLocal<>();

        abstract String askAndAnswer(String systemPrompt, String userPrompt, String model);
    }

    static final class OfflineApi extends EvalApi {
        private final Fixture fixture;

        OfflineApi(Fixture fixture) {
            this.fixture = fixture;
        }

        @Override
        String askAndAnswer(String systemPrompt, String userPrompt, String model) {
            requests.incrementAndGet();
            if (fixture.transportFailure) {
                throw new IllegalStateException("SYNTHETIC_TRANSPORT_FAILURE");
            }
            require(fixture.offlineResponse != null);
            try {
                String content = fixture.offlineResponse.isTextual() ? fixture.offlineResponse.textValue()
                        : JSON.writeValueAsString(fixture.offlineResponse);
                return content.repeat(fixture.offlineResponseRepeat);
            } catch (IOException ignored) {
                throw new IllegalStateException("SYNTHETIC_TRANSPORT_FAILURE");
            }
        }
    }

    private static final class LiveApi extends EvalApi {
        private final CompletionPathOpenAiApi delegate;

        private LiveApi(EvalProviderConfig config) throws IOException {
            JSONObject extra = config.extraBody() == null ? null
                    : new JSONObject(JSON.convertValue(config.extraBody(), Map.class));
            delegate = new CompletionPathOpenAiApi(config.baseUrl(), config.apiKey(), config.completionPath(),
                    new AIConfigHelper().buildRestClient(60), new ResponseErrorHandler() {
                        @Override
                        public boolean hasError(ClientHttpResponse response) throws IOException {
                            return !response.getStatusCode().is2xxSuccessful();
                        }

                        @Override
                        public void handleError(ClientHttpResponse response) throws IOException {
                            throw new IOException("MODEL_REQUEST_FAILED");
                        }
                    }, extra) {
                @Override
                public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest request) {
                    requests.incrementAndGet();
                    ResponseEntity<ChatCompletion> response = super.chatCompletionEntity(request);
                    ChatCompletion body = response.getBody();
                    if (body != null) {
                        metadata.set(new String[]{config.safeResponseIdentifier(body.model()),
                                config.safeResponseIdentifier(body.systemFingerprint())});
                    }
                    return response;
                }
            };
        }

        @Override
        String askAndAnswer(String systemPrompt, String userPrompt, String model) {
            OpenAiChatClient client = new OpenAiChatClient(delegate,
                    OpenAiChatOptions.builder().withModel(model).build(), null,
                    RetryTemplate.builder().maxAttempts(1).build());
            OpenAIService openAI = new OpenAIService() {
                @Override
                protected OpenAiChatClient getClient() {
                    return client;
                }
            };
            return openAI.askAndAnswer(systemPrompt, userPrompt);
        }
    }

    private record Options(String mode, Path properties, List<Path> envFiles, int concurrency) {
        static Options parse(String[] args) {
            String mode = null;
            Path properties = null;
            List<Path> env = new ArrayList<>();
            int concurrency = 1;
            boolean concurrencySet = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--offline", "--evaluate", "--describe" -> {
                        require(mode == null);
                        mode = args[i];
                    }
                    case "--properties" -> {
                        require(properties == null && i + 1 < args.length);
                        properties = Path.of(args[++i]);
                    }
                    case "--env-file" -> {
                        require(i + 1 < args.length && env.size() < 10);
                        env.add(Path.of(args[++i]));
                    }
                    case "--concurrency" -> {
                        require(!concurrencySet && i + 1 < args.length);
                        concurrencySet = true;
                        concurrency = Integer.parseInt(args[++i]);
                        require(concurrency >= 1 && concurrency <= 3);
                    }
                    default -> throw EvalProviderConfig.rejected();
                }
            }
            require(mode != null);
            require(mode.equals("--offline") ? properties == null && env.isEmpty() : properties != null);
            return new Options(mode, properties, List.copyOf(env), concurrency);
        }
    }
}
