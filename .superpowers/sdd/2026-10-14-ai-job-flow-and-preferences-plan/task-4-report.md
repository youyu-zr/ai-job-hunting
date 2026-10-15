# Task 4 Report: Browser Message Transport and Execution

## Status

Task 4 is implemented and locally verified. It forwards allow-listed BOSS body
type metadata, no longer makes local semantic promotion decisions, maps the
Task 3 stop reasons, and preserves oversized merged messages without treating
them as replied. No backend, preference, job-screening, deployment, or docs
scope was changed except this required task report.

## Behavior

- `body.type` is forwarded only when it is a numeric allow-listed value:
  `1`, `4`, `8`, `12`, or `14`. Text is never used to infer the type.
- Known protocol types `4`, `8`, and `12` retain their existing local event
  suppression. Type `14` reaches the server confirmation path.
- Ordinary type-1 HR text containing words such as “系统通知”, “抖音”,
  “账号”, or “收费” is eligible for server classification. `marketingGate.ts`
  is now a non-blocking compatibility shim; its keyword classifier is removed.
- Packet sender identity is compared with the target contact before the AI
  path. Existing account/session/duplicate/send-boundary checks remain.
- The full normalized merged text is measured before the 5000-character
  ceiling. Oversized messages are not sent or truncated for classification.
  They get the visible notice “消息内容较长，未自动回复；请打开会话查看” and a
  terminal local dedupe marker with `replySent=false`, preventing unread
  recovery from repeatedly retrying without claiming a successful reply.
- `PROMOTION_SILENT` completes only the current message and leaves the seat
  running; `HIGH_RISK_JOB` and `USER_CONFIRMATION_REQUIRED` show distinct
  current-job/session notices; `AI_PROCESSING_ERROR` remains visible and
  retryable without being marked answered.

## TDD Evidence

### RED

- `node --experimental-strip-types --test tests/marketingGate.test.ts tests/messageBody.test.ts tests/packetMessage.test.ts tests/replyDisposition.test.ts` failed as expected: the browser still referenced its semantic marketing gate, and `normalizedMessageBatch` was not implemented.
- `node --experimental-strip-types --test tests/messageCompletion.test.ts` failed as expected when the regression asserted that a terminal unresolved entry must be deduplicated but must have `replySent=false`.

### GREEN

Focused final command:

```powershell
node --experimental-strip-types --test tests/marketingGate.test.ts tests/messageBody.test.ts tests/packetMessage.test.ts tests/replyDisposition.test.ts tests/messageCompletion.test.ts tests/adversarialPreRelease.test.ts
```

Result: `41` tests passed, `0` failed.

Additional verification:

- `pnpm build` passed (`vue-tsc` and Vite production build). Existing dependency/deprecation `eval` warnings remain.
- Task 3 independent review: `mvn -o '-Dtest=JobSeekerClonedServiceMessageDecisionTest,JobSeekerClonedServiceMarketingTest,JobSeekerClonedServiceHumanHandoffTest,JobSeekerClonedServiceReplyGuardTest,ConversationGateUtilTest,MessageTriageServiceTest' test` passed `64/64`.
- `git diff --check` passed.
- Full `pnpm test` ran `158` tests: `156` passed and `2` failed in pre-existing, out-of-scope dirty drafts: `frontend/tests/filterGate.test.ts` imports missing `shouldRetryAfterAiFilter`; `frontend/tests/preferenceDefaults.test.ts` has a TypeScript syntax error. Neither file is changed by Task 4.

## Task 3 Independent Review

Reviewed the committed server integration and its follow-up fix `571f971`.
The current-job/session stop scope and retryable processing-error contract are
consistent with the browser mapping in this task. The focused backend suite
passes. Mocked service tests do not prove real-model classification accuracy,
and this review does not verify live BOSS protocol behavior or platform
authorization.

## Changed Files

- `frontend/src/platform/aiPower.ts`
- `frontend/src/platform/bossPlatform.ts`
- `frontend/src/platform/marketingGate.ts`
- `frontend/src/platform/messageCompletion.ts`
- `frontend/src/platform/replyDisposition.ts`
- `frontend/src/platform/utils.ts`
- `frontend/src/webSocket/messageBody.ts`
- `frontend/src/webSocket/packetMessage.ts`
- `frontend/tests/adversarialPreRelease.test.ts`
- `frontend/tests/marketingGate.test.ts`
- `frontend/tests/messageBody.test.ts`
- `frontend/tests/messageCompletion.test.ts`
- `frontend/tests/packetMessage.test.ts`
- `frontend/tests/replyDisposition.test.ts`

## Remaining Live Protocol Assumptions

- The observed numeric BOSS `body.type` meanings and their stability require
  acceptance against a real BOSS account. In particular, type `14` is treated
  as an interview card requiring confirmation; a synthetic packet test cannot
  prove that mapping remains valid in the live protocol.
- A packet with no structured sender ID is left to the existing upstream
  identity/recovery checks; absence of that metadata alone is not used to infer
  a sender.
- No live BOSS account acceptance, current platform authorization, remote CI,
  push, merge, or deployment was performed by Task 4.
