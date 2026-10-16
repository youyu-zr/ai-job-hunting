# Task 7 Report: Documentation Synchronization

Date: 2026-10-15
Scope: Documentation and acceptance cases only. No Task 4/6 implementation or generated userscript was edited.

## Result

Updated the eight files listed by `task-7-brief.md`:

- `README.md`
- `install.html`
- `docs/USER_GUIDE.md`
- `frontend/src/assets/UseDocument.md`
- `docs/ACCEPTANCE.md`
- `docs/DATA_FLOW_AND_RETENTION.md`
- `docs/PROJECT_STATUS_2026-10-14.md`
- `docs/PROJECT_PROGRESS.md`

The documents now explain that the browser reads BOSS page data and executes page actions, while server-side AI classifies job and HR-message meaning using context, resume, and saved preferences. They describe low-risk incomplete job details as deliverable, ordinary promotion as silent by default with an editable user preference, salary/location/contact handling through explicit preferences, and current-session confirmation for concrete interview time/card or explicit commitments. Structured BOSS events are identified from protocol metadata, not ordinary message keywords. Model/API/format failure does not send or deliver automatically.

Added the 11 requested acceptance scenarios: ordinary Douyin operator role, course/group funnel, explicit upfront fee, incomplete listing, filled salary/city preferences, missing preferences, concrete interview invitation, contract-details inquiry, structured protocol event, model/format failure, and custom preference persistence. They are explicitly unchecked requirements, not claimed test results.

## Fresh Status Evidence

- On 2026-10-15, `https://aijob.zxpnb.top/install.html` returned HTTP 200 and identified `0.0.116-beta-identity-ready`.
- On 2026-10-15, `https://aijob.zxpnb.top/ai-job-hunting.user.js` returned HTTP 200 and its userscript header identified `0.0.116-beta-identity-ready`.
- GitHub PR #2 is OPEN and targets `release/open-source-20261011`. Its remote head remains `0b6b94359b7f5a1125ac2227f332fd0f04a4cd6a`, not the current locally committed candidate HEAD `03c48f9`; Task 6 implementation, Task 7 wording corrections, and the prelaunch smoke correction are additional uncommitted working-tree changes. The passing PR CI and Java/TypeScript CodeQL checks correspond only to that remote old head. No CI result for the current local candidate was confirmed; it has not been pushed, approved, merged, or deployed.
- GitHub API reports `main` protected with one required PR approval and required `frontend` and `backend` checks. `release/open-source-20261011` is not protected.
- BOSS current authorization boundary and real-account end-to-end acceptance remain unresolved.

## Verification

- `pnpm test`: PASS, 171 tests, 0 failures.
- `pnpm build`: PASS. Build emitted existing `eval` and Node deprecation warnings; no build failure.
- `node scripts/settings-clarity.smoke.mjs`: PASS.
- Documentation assertions: PASS for all 11 acceptance scenario labels and the current version/PR/branch-protection facts.
- `git diff --check`: PASS; only line-ending conversion warnings were reported.
- `pnpm verify:prelaunch`: BLOCKED/FAIL, 5 of 6 checks pass. The remaining legacy smoke assertion requires old deterministic pausing for personal-decision messages. That expectation conflicts with this plan's preference-based handling of salary/location/contact and the narrower interview/explicit-commitment handoff. The smoke script is outside Task 7 scope and was not changed.
- `pnpm verify:public`: FAIL because root `ai-job-hunting.user.js` differs from the current frontend build beyond path-derived identifiers. This is the candidate artifact-parity issue amid parallel Task 4/6 work; Task 7 is documentation-only, so neither source nor generated artifact was modified.
- Backend tests/package were not run because this task changes documentation only.

## Release State

Task 7 documentation synchronization is complete locally. It does not establish candidate release readiness. The exact current candidate still needs Task 4/6 completion, artifact synchronization, current-head remote CI/CodeQL, maintainer approval, release-branch protection, BOSS authorization review, and real-account acceptance. No deployment was attempted.
