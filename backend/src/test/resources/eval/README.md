# Task8 Controlled Semantic Evaluation

This is a standalone test entry point, not an application feature. It creates
the production `MessageTriageService` without a Spring context. Offline calls
receive synthetic responses directly through its two-argument facade.
Live calls use the real `OpenAIService`, `OpenAiChatClient`, and production
`CompletionPathOpenAiApi` with bounded HTTP helpers.
It never calls application startup, BOSS, a database, session APIs, persistence,
or message delivery.

Spring AI 0.8.1-SNAPSHOT's installed `OpenAiApi` constructors always create an
async `WebClient` and expose no async-builder parameter. Offline therefore
does not extend or construct `OpenAiApi` or any HTTP client. No constructor
bypasses, additional dependencies, JUnit, or Mockito are needed at runtime.
The existing `OpenAIServicePromptRolesTest` independently verifies production
role construction. End-to-end role serialization, model selection,
non-streaming transport, response envelopes, and the `OpenAIService` wrapper
remain live-only coverage, not claims made by the offline checks.

## Build and Offline Verification

From `backend`:

```text
mvn -o test-compile
mvn -o -Dtest=ControlledSemanticEvalTest,OpenAIServicePromptRolesTest -DargLine=-Xmx192m test
```

No credentials or configuration files are needed for `--offline`. Its 16
protocol, input-limit, real-parser, and technical-failure checks use an
in-memory transport with no HTTP client construction. Its 31 semantic cases
print `NOT_EVALUATED`.
Offline results are deterministic contract checks, not transport or semantic
proof. Offline records identify the synthetic model as `OFFLINE_CONTRACT_ONLY`;
`offlineRequests` counts synthetic facade invocations, not HTTP requests.

## Private Staging Layout

Copy only the compiled harness classes and synthetic resource, preserving these
paths below an absolute, controller-owned private staging directory:

```text
test-classes/com/maple/ai/job/hunting/eval/ControlledSemanticEval*.class
test-classes/com/maple/ai/job/hunting/eval/EvalProviderConfig.class
fixtures/eval/task8-synthetic-hr.json
```

The class files come from `backend/target/test-classes/com/maple/ai/job/hunting/eval/`.
The JSON comes from `backend/src/test/resources/eval/task8-synthetic-hr.json`
(also copied by Maven to `backend/target/test-classes/eval/`).
Do not copy production configuration, test-framework dependencies, unrelated
test classes, or an entire `target/classes` directory into staging.

`loader.path` entries are classpath roots: the directory above `com/` and
the directory above `eval/`, not those package directories themselves.
Alternatively put the resource at `test-classes/eval/task8-synthetic-hr.json`
and use just `test-classes` as the loader path.

Use the production fat JAR that contains the candidate production triage,
parser, prompt, and role-separated OpenAIService APIs. Harness class files do
not replace production code. An older JAR is not evidence about the candidate.
The harness needs no JUnit or Mockito at runtime.

## Remote CLI

Run only on the authorized host. Define `FAT_JAR` as the absolute existing
production fat JAR path and `EVAL_STAGE` as the absolute private staging root.
The launcher below is Spring Boot 3.2's PropertiesLauncher. It does not invoke
the application's normal main class or restart any service.

```sh
java -Xmx192m \
  -Dloader.main=com.maple.ai.job.hunting.eval.ControlledSemanticEval \
  -Dloader.path="$EVAL_STAGE/test-classes/,$EVAL_STAGE/fixtures/" \
  -cp "$FAT_JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
  --offline
```

Describe first, without a model request:

```sh
java -Xmx192m \
  -Dloader.main=com.maple.ai.job.hunting.eval.ControlledSemanticEval \
  -Dloader.path="$EVAL_STAGE/test-classes/,$EVAL_STAGE/fixtures/" \
  -cp "$FAT_JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
  --describe \
  --properties /private/config/application.properties \
  --env-file /private/config/secrets.env
```

After the controller reviews that descriptor:

```sh
java -Xmx192m \
  -Dloader.main=com.maple.ai.job.hunting.eval.ControlledSemanticEval \
  -Dloader.path="$EVAL_STAGE/test-classes/,$EVAL_STAGE/fixtures/" \
  -cp "$FAT_JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
  --evaluate --concurrency 1 \
  --properties /private/config/application.properties \
  --env-file /private/config/secrets.env
```

## Configuration and Safety Boundaries

- Only explicitly supplied files are read. No ambient process environment,
  system-property credentials, default config locations, remote config reads,
  or database overrides are used. Never fetch the remote files locally.
- Application properties use Java `Properties` with a UTF-8 reader. Env files
  accept `NAME=value`, optional `export `, whole-value single/double quotes,
  blank lines, and full-line comments. They are parsed as data, never sourced.
  No shell expansion, inline comments, multiline values, or escape decoding is
  performed. Conflicting duplicate env values are rejected. `--env-file` can be
  repeated up to ten times.
- `${NAME:default}` and nested placeholders resolve against the supplied env
  files first, then the supplied properties. Missing/cyclic placeholders fail
  closed. Pool JSON is parsed structurally with duplicate-key rejection.
- A nonempty `openai.pool.config.list` wins over automatic `spring.ai.NAME.*`
  discovery, matching production. Exactly one `chat.options.first=true` must
  resolve. Dotted/dashed option aliases are supported. No random pool fallback.
- `--describe` prints only provider, model, host (no URL path/query/user info),
  a fixed `askCompatibility` status, and
  `runtimeDbOverrides=UNAVAILABLE_NOT_CHECKED`. If `use-ai-map` is present,
  `ask` must be `openai-pool`; otherwise it reports
  `FILE_ASK_ROUTE_INCOMPATIBLE` and exits 2. If the mapping is absent it reports
  `DEFAULT_POOL_FILE_MAPPING_ABSENT`, not a claim about the running ask route.
  `--evaluate` rejects an incompatible file mapping.
- Dynamic database overrides are deliberately unavailable. Even a compatible
  file mapping proves only the supplied file's pool selection, not the current
  running service's effective route. The controller must separately establish
  that correspondence before claiming production semantic acceptance.
- Only HTTPS public model endpoints and relative completion paths are accepted.
  Proxy-enabled primary pools and tool/function extra bodies are rejected
  because this harness cannot reproduce them safely. Production endpoint
  validation and bounded-response HTTP helpers are reused.
- Default concurrency is 1, maximum 3. Each wave has a hard 60-second deadline;
  transport read timeout is 60 seconds and there is one attempt, no retries.
  After a timeout, no later waves are dispatched. Remaining cases are reported
  `NOT_EVALUATED` and the run fails. Evaluate rejects a JVM heap above 192 MiB.
- All fixture resumes are absent. Salary/city/contact preferences are explicitly
  synthetic data, never inferred candidate facts. The harness only evaluates
  category/action; it does not generate replies or prove absence of every
  possible hallucination in model explanations.

## Reports and Exit Codes

Evaluation emits JSON lines containing only case ID, expected/actual category
and action, response model/fingerprint identifiers, and a fixed `diagnosticCode`,
followed by counts.
If a response omits the version/fingerprint it is `UNREPORTED`, never invented.
Identifiers are length/character constrained. Raw config, credentials,
requests, responses, reasons, stack traces, and model error bodies are never
printed. Library logging is disabled in the standalone main.

Failure diagnostics come from the production classifier's optional enum-only
observer. Business results still use `PROCESSING_ERROR`, `NO_ACTION_RETRY`, and
`AI_PROCESSING_ERROR`; no parser rejection, size bound, or action guard is relaxed.
`RESPONSE_EMPTY`, `RESPONSE_TOO_LARGE`, `RESPONSE_JSON_INVALID`,
`RESPONSE_FIELDS_INVALID`, `RESPONSE_CATEGORY_INVALID`, `RESPONSE_ACTION_INVALID`,
`RESPONSE_REASON_CODE_INVALID`, `RESPONSE_REASON_INVALID`,
`RESPONSE_QUESTIONS_INVALID`, and `RESPONSE_EVIDENCE_INVALID` distinguish strict
response validation failures. `MODEL_REPORTED_ERROR` means the model itself
returned a structurally valid processing-error decision.
`ACTION_CONTRACT_REJECTED` means a parsed category/action pair was disallowed.
`MODEL_CALL_FAILED` covers failures before the role-separated service returned
content, including transport, response-envelope, or chat-client failures; it
does not identify a specific provider error. Other fixed codes are
`INPUT_REJECTED`, `PAYLOAD_REJECTED`, and `RESULT_PROCESSING_FAILED`.
No diagnostic includes model text, field values, exception names/messages,
stack traces, URLs, headers, or credentials. Successful/skipped records use
`NONE`; harness-only failures use `REQUEST_TIMEOUT` or `EVAL_EXECUTION_FAILED`.
`DIAGNOSTIC_UNAVAILABLE` is not evidence of a particular failure cause.
Capture these codes on synthetic live cases; do not enable raw provider logging.

`semanticChecked`, `semanticSkipped`, `offlineChecked`, `liveRequests`, and
`offlineRequests` distinguish actual model work from deterministic checks.
False-positive/negative counts are separate for high-risk and promotion cases;
individual expected/actual records identify the mismatches.

Exit 0 means the selected checks matched (offline can still skip all semantics).
Exit 1 means a mismatch, request failure, timeout, or incomplete live run.
Exit 2 means rejected configuration/arguments or an internal harness failure.
Do not use offline exit 0 as a release gate for semantic accuracy.
After any prompt change, a controller-run live evaluation against the updated
candidate production JAR is mandatory before deployment. Local contract tests
cannot prove the semantic failures from an older candidate are corrected.

## Repair Verification: 2026-10-16

The controller's `7dd1301` live baseline reported 18 failures among 47 total
cases (16 offline, 31 semantic), including four processing errors and no wave
timeouts. That baseline did not retain diagnostic codes, so the four exact
rejection causes cannot be reconstructed from category/action records alone.

The repair narrows uncertainty to missing intent-critical facts, distinguishes
ordinary commitment confirmation from recruitment risk, makes promotion opt-in
and refusal policy precedence explicit, and documents the existing strict
response/action contract in the prompt. Fixture labels and parser acceptance
guards are unchanged. The production observer is no-op unless explicitly
overridden by the evaluator; business error results remain unchanged.

Local TDD: 40 focused tests first produced seven assertion failures, then all
40 passed. Broader caller verification and packaging passed with 71 tests,
zero failures/errors/skips; `mvn -o test-compile` also passed. A standalone
PropertiesLauncher `--offline` run against the rebuilt JAR exited 0 with
`offlineChecked=16`, `passed=16`, `failed=0`, `semanticSkipped=31`, and
`liveRequests=0`, without JUnit/Mockito runtime jars.

The updated live run is pending controller execution. This is not semantic
acceptance and is not permission to deploy.
