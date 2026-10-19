# Resume Identity Transport Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore authenticated resume import through a normal reverse proxy without weakening identity checks or misreporting failures as missing resumes.

**Architecture:** Use `X-Boss-Token` only between the userscript and this application's backend. Keep the platform's `Zp_token` header on requests to BOSS. The backend accepts the canonical header and a compatible legacy fallback, rejects conflicting proof, and shares proof selection with the request limiter. Improve import error reporting and advertise a conservative retry delay without automatic resubmission.

**Tech Stack:** Existing Vue/TypeScript/Axios userscript, Spring Boot/Servlet Java 17, Nginx reverse proxy, Node tests, JUnit.

**Spec:** The evidence and acceptance requirements in this document.

## Evidence And Acceptance Requirements

- Production requests first failed identity verification before resume parsing; later manual retries received HTTP 429.
- The active proxy configuration has no override accepting underscore headers.
- An isolated instance of the installed Nginx reproduced the boundary: `Zp_token` reached a direct echo handler but disappeared through the proxy; `X-Boss-Token` survived. Only synthetic proof was used.
- Existing tests checked header source strings and individual rate-limit values, not a real proxy boundary.
- A successful HTTP connection is not authenticated account creation or resume import.

## Global Constraints

- Do not disable identity verification, invent ownership proof, copy browser login credentials into source, or put a model key into a userscript.
- Do not change calls made to the BOSS platform to use an application-specific header.
- Preserve account/server scope checks and per-chat/global pause controls.
- Keep rate limits and model concurrency protection; do not retry resume uploads automatically.
- Preserve unrelated working-tree changes and all existing Git history.
- Deploy only this application's artifacts; no database migration, global proxy change, firewall change or unrelated service restart.
- Clearly distinguish synthetic integration tests, real-model calls and authenticated BOSS acceptance.

## Task 1: Backend Header Compatibility And Retry Information

**Files:** `backend/src/main/java/com/maple/ai/job/hunting/utils/BossTokenHeaders.java`, `controller/UserController.java`, `frame/filter/RequestRateLimitFilter.java`, `frame/filter/CorsFilter.java`, with focused tests in the corresponding existing test packages.

**Interfaces:**
- Consumes canonical `X-Boss-Token` and legacy `Zp_token` request headers.
- Produces `BossTokenHeaders.resolve(String canonical, String legacy): String`.
- Both nonblank and unequal values fail with the existing identity-verification business error. Blank canonical values may use a nonblank legacy value; neither value authenticates a user by itself.

- [x] Add failing tests for canonical/legacy/equal/conflicting/missing proof, actual controller binding, limiter identity selection, CORS preflight and exposed `Retry-After`.
- [x] Run the focused tests and observe the missing canonical behavior before implementation.
- [x] Implement the resolver and use it at both identity-bearing endpoints and the anonymous limiter key.
- [x] Keep verifier ownership checks unchanged. Give identity throttling a conservative delay based on its configured rate, rather than always advertising one second.
- [x] Rerun focused tests and the full backend suite. Do not update tests merely to assert source spelling.

## Task 2: Browser Proof Transport And Honest Import Failures

**Files:** `frontend/src/axios.ts`, `frontend/src/utils/bossIdentityTransport.ts`, `frontend/src/components/ui/AiJob.vue`, small import-error helper if needed, and focused frontend tests.

**Interfaces:**
- `installBossIdentityTransport(client: AxiosInstance): void` installs canonical proof transport on the application's client only.
- The real Axios adapter must receive `X-Boss-Token`; platform clients retain `Zp_token`.
- Error notices distinguish attachment lookup/download, application identity rejection, HTTP 429, and transport failure. Already displayed application errors must not create a second misleading generic toast.

- [x] Reproduce the missing canonical header using a real Axios instance and a synthetic adapter.
- [x] Add import-error regressions for application 429 versus missing attachment, failed identity proof versus file parsing, and duplicate notification suppression.
- [x] Apply minimal scoped changes. Read a ready live identity before import, keep account/server/token guards, and prevent overlapping import actions.
- [x] Preserve successful-import status if only later session refresh fails; avoid a redundant login request after storing a fresh session.
- [x] Validate download status before treating a response as a resume file. Do not silently retry a failed platform action.
- [x] Run focused tests, all frontend tests, prelaunch checks, build and public-userscript parity verification.

## Task 3: Verification, Version And Controlled Release

**Files:** `frontend/package.json`, relevant public version references, generated public userscript, release evidence and status documentation.

- [x] Update the script release to `0.0.119-beta-resume-import` without changing user-facing installation instructions.
- [x] Review both workers' diffs, reproduce important failure tests independently and obtain an independent final source/adversarial review.
- [x] Prove that the generated userscript matches reviewed frontend source; ensure no credentials or private probe artifacts are staged.
- [x] Push a scoped branch, require current-head frontend/backend and code-scanning checks, and merge normally under the owner's existing delegation.
- [x] Package and hash the backend, userscript and install page. Preserve a private rollback set and compare unrelated service IDs/start times.
- [x] Replace only application artifacts and restart only the application's service. Verify HTTPS artifacts and explicit header/CORS transport behavior without using a real account unless one is actually available.
- [x] Report the old failure chain, the repaired chain, exact verification boundary and a single concrete owner-operated import acceptance step. Do not call public reachability authenticated acceptance.
