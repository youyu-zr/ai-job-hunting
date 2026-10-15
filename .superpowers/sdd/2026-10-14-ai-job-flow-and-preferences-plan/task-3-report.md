# Task 3 Report: Integrate HR Message Decisions

## Status

Task 3 is implemented and locally verified. This commit contains only the
Task 3 backend production files, Task 3 regression tests, and this report.

## What Changed

- Added optional `platformBodyType` to `JobSeekerClonedParam`.
  - Only supported values `1`, `4`, `8`, `12`, and `14` are retained.
  - Unknown values are cleared.
  - The value is supplied as metadata; it is never inferred from message text.
- Injected `MessageTriageService` into `JobSeekerClonedService`.
  - Authentication/session and duplicate checks remain first.
  - Reliable protocol noise is filtered before semantic classification.
  - The classifier receives the current HR message, job context, stored resume
    text, saved preference object, and platform body type.
- Made the classifier decision authoritative before any resume operation,
  `sessionChat`, or outgoing reply:
  - `PROCESSING_ERROR` / `NO_ACTION_RETRY` returns retryable
    `AI_PROCESSING_ERROR`.
  - Classification failures remove the duplicate marker so the same message
    can be retried.
  - `PROMOTION` becomes `PROMOTION_SILENT` for the current message only.
  - `HIGH_RISK` becomes `HIGH_RISK_JOB` and sets only the current job gate.
  - A concrete free-text interview date/time or explicit contract/offer
    commitment becomes `USER_CONFIRMATION_REQUIRED` and pauses only that job
    session.
  - Reliable `platformBodyType=14` continues to use the previously decided
    `PLATFORM_EVENT / NEEDS_USER_CONFIRMATION` protocol exception and pauses
    only the current job session, even when the card text itself does not
    contain a visible time.
  - Ordinary contact, salary, location, and uncertain messages continue to
    the AI conversation instead of being paused by broad keywords.
  - Resume requests remain supported, but can no longer bypass high-risk or
    processing-error decisions.
- Narrowed `ConversationGateUtil.humanHandoffCategory` so contact, salary,
  location, and generic discussion are not deterministic pause triggers.
  Concrete interview/start-date commitments and explicit contract/offer
  commitments remain covered.
- Added a retryable flag to `JobSeekerClonedResultVO`.

## TDD Evidence

### RED

Command:

```powershell
mvn -o '-Dtest=JobSeekerClonedServiceMessageDecisionTest' test
```

Result: failed during test compilation because `JobSeekerClonedParam` did not
yet expose `getPlatformBodyType()` / `setPlatformBodyType(...)`. This was the
expected missing-interface failure for the new Task 3 tests.

After the first implementation pass, the focused test executed and exposed
one incorrect test expectation for a contract-duration discussion. The
implementation correctly continued that discussion; the test was corrected
to require no pause.

### GREEN

Command:

```powershell
mvn -o '-Dtest=JobSeekerClonedServiceMessageDecisionTest,JobSeekerClonedServiceMarketingTest,JobSeekerClonedServiceHumanHandoffTest,JobSeekerClonedServiceReplyGuardTest,ConversationGateUtilTest,MessageTriageServiceTest' test
```

Result: `63` tests run, `0` failures, `0` errors, `0` skipped.

Additional packaging verification:

```powershell
mvn -o -DskipTests package
```

Result: `BUILD SUCCESS`; the backend JAR was produced locally.

Additional hygiene verification:

```powershell
git diff --check
```

Result: no whitespace errors reported.

## Changed Files

- `backend/src/main/java/com/maple/ai/job/hunting/model/param/JobSeekerClonedParam.java`
- `backend/src/main/java/com/maple/ai/job/hunting/model/vo/JobSeekerClonedResultVO.java`
- `backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedService.java`
- `backend/src/main/java/com/maple/ai/job/hunting/utils/ConversationGateUtil.java`
- `backend/src/test/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedServiceMessageDecisionTest.java`
- `backend/src/test/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedServiceMarketingTest.java`
- `backend/src/test/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedServiceHumanHandoffTest.java`
- `backend/src/test/java/com/maple/ai/job/hunting/utils/ConversationGateUtilTest.java`
- `.superpowers/sdd/2026-10-14-ai-job-flow-and-preferences-plan/task-3-report.md`

## Residual Concerns

- The tests use a recording `MessageTriageService` for service integration.
  They prove ordering, action mapping, scope, and send guards; they do not
  prove live model semantic accuracy. Task 8 still needs controlled real-model
  classifier evaluation and prompt-injection checks.
- `platformBodyType=14` follows the existing Task 2/plan ruling as a
  structured interview-card exception. It pauses the current job session even
  when the card text lacks a visible time. Free-text messages do not receive
  that exception.
- The current session pause and job gate mechanisms are existing runtime
  mechanisms. Structured interview calendar conflict detection and
  cross-device calendar synchronization remain deferred by the plan.
- This is a local feature-branch commit only. It has not been pushed, merged,
  deployed, or accepted against a real BOSS account.
