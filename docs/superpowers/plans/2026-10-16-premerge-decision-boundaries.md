# Pre-Merge Decision Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Complete each regression's RED/GREEN cycle before integration.

**Goal:** Close two concrete review counterexamples before merging PR #2: concurrent mixed-type HR messages bypassing a pending confirmation, and malformed classifier responses accepted by substring extraction.

**Architecture:** Retain the existing browser/server split, per-account state, semantic classifier, and fail-closed action contract. Validate an entire classifier response with the existing Jackson parser. Treat a same-conversation burst as one safety decision without losing protocol metadata; do not merely serialize partitions that still permit a resume-first bypass.

**Tech Stack:** TypeScript with Node's test runner, Vue userscript, Java with Jackson, JUnit and Maven.

**Spec:** `docs/superpowers/specs/2026-10-14-ai-job-flow-and-preferences-design.md`; the existing delivery and confirmation requirements, not a new feature.

## Global Constraints

- Preserve the three pre-existing whitespace-only frontend modifications.
- Do not change model prompts, sampling, provider configuration, personal preferences, payment, database schema, or unrelated services.
- No external model, real resume, BOSS message, or credential is needed to reproduce either counterexample.
- Keep JSON-format recovery bounded to one fresh classification using the original trusted input.
- Preserve normal safe replies and resume requests; confirmation and risk must prevent outgoing actions for the relevant burst.
- Do not change protected-branch approval requirements without an explicit owner decision.

## Task 1: Strict Complete Classifier Response

**Files:** `backend/src/main/java/com/maple/ai/job/hunting/service/biz/MessageTriageService.java`, `backend/src/test/java/com/maple/ai/job/hunting/service/biz/MessageTriageServiceTest.java`, and `MessageTriageServiceDiagnosticsTest.java`.

**Interface:** `classify(...)` still returns `MessageTriageResultVO`; invalid complete output returns `PROCESSING_ERROR/NO_ACTION_RETRY` after at most one repair.

- [x] Add separate regression cases for a valid object wrapped in an array and a valid object followed by a comma; require no outgoing action.
- [x] Cover trailing primitive tokens, non-whitespace prose, duplicate objects, and duplicate fields; retain pure-object and optional full Markdown-fence support.
- [x] Run `mvn -Dnet.bytebuddy.experimental=true -Dtest=MessageTriageServiceTest,MessageTriageServiceDiagnosticsTest test`; confirm the new cases fail because permissive actions were returned. RED: 38 tests, 4 expected assertion failures.
- [x] Replace first-object extraction with full structured parsing and root-object validation, using the existing JobFilterService parser pattern.
- [x] Rerun focused tests; verify repair still uses identical trusted input and no third request. GREEN: 38 tests, no failures or errors.

## Task 2: Mixed-Protocol Conversation Safety

**Files:** `frontend/src/platform/messageMerge.ts`, `frontend/src/platform/bossPlatform.ts`, `frontend/src/platform/messageBatchMetadata.ts`, `frontend/tests/messageMerge.test.ts`, and `frontend/tests/marketingGate.test.ts`. Follow-up review additionally identified the same identity collision in `backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedService.java`; cover it in `JobSeekerClonedServiceMessageDecisionTest.java`.

**Interface:** Keep `MessageMergeBatch`'s per-item metadata and existing message completion handling.

- [x] Reproduce a slow confirmation text plus a fast resume card in both arrival orders using the real queue. Initial RED: 15 of 18 failed.
- [x] Cover high-risk text plus resume request and any interview card plus ordinary text; assert no outgoing resume/reply for the combined unsafe burst.
- [x] Replace independently actionable body-type partitions with one conversation-level safety decision, preserving interview protocol semantics.
- [x] Cover independent conversations and recovery after a failed processing callback; do not allow one failure to permanently block a queue.
- [x] Preserve same-preview interview packets at browser ingress and during batch cleanup; RED: 3 of 45 failed, GREEN: all 45 passed, expanded focused set 78 passed.
- [x] Reproduce backend cross-window type-1/type-14 text identity collision; RED: one assertion returned DUPLICATE_MESSAGE instead of USER_CONFIRMATION_REQUIRED. Use an interview-only suffix, preserving normal null/type-1 and repeated-card deduplication.
- [x] Run focused Node tests and then the full frontend suite. Controller: 222 passed. Backend full package: 399, no failures/errors, 2 external-service skips.

## Task 3: Review And Integration

- [ ] Independently review the actual fixes and regression results; resolve blocker/high findings.
- [ ] Run frontend tests, prelaunch checks, build/public-artifact checks, and the full backend suite.
- [ ] Commit only scoped fixes, their tests, this plan and accurate verification notes; push without force.
- [ ] Verify all GitHub checks correspond to the new head before merging PR #2 into its existing release base.
- [ ] Explain the remaining `main` approval-policy blocker if no owner decision is available; do not weaken protection to hide it.
- [ ] Report exact old/new behavior, the concrete cause, the complete action sequence, validation limits, and next responsibilities in plain Chinese.
