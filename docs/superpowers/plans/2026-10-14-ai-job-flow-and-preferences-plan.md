# AI 求职沟通与偏好判断 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让服务器 AI 统一判断岗位和 HR 消息语义，用用户可编辑的默认偏好指导薪资、地点、联系方式及推广消息处理，并在不确定内容与系统故障之间做出不同动作。

**Architecture:** 浏览器只采集 BOSS 消息元数据、发送者、会话状态并执行发送；服务端 `MessageTriageService` 在现有 AI 坐席请求中执行结构化语义分类，再决定正常回复、默认推广静默、高风险阻断、本人确认或技术失败。岗位筛选另有清晰的 `DELIVER` / `FILTER` / `PROCESSING_ERROR` 动作；岗位资料不足但无明确风险时继续投递。

**Tech Stack:** Vue 3、TypeScript、Node test runner、Spring Boot、Java 17、JUnit 5、Maven、pnpm。

**Spec:** `docs/superpowers/specs/2026-10-14-ai-job-flow-and-preferences-design.md`

## Global Constraints

- 不修改或直接推送 `main`；先核实当前远端分支和 PR，再通过受保护分支及维护者审核合并。
- 不清理或覆盖实现开始前已有的工作区差异；先检查归属并保留。
- 浏览器不得接触模型 API Key；服务端负责最终语义分类和外发动作闸门。
- 不因“推广、抖音、销售、收费、账号、返佣”等单个词自动过滤；必须结合具体主体、动作、岗位职责和上下文。
- 岗位内容不完整但无明确风险或用户条件冲突时继续投递；AI/API/解析技术故障时不投递。
- 联系方式、薪资和地点使用用户偏好，不因出现话题本身就暂停；不得编造简历事实或联系方式。
- 只有需要本人接受具体面试日期/时间或作出同等级明确承诺时暂停当前岗位会话，不关闭账号级坐席或其他会话。
- 不新增数据库结构迁移；新偏好字段兼容现有 JSON 偏好保存和旧客户端。
- 不记录原始 HR 消息、简历、联系方式或完整提示词。
- 不通过随机延时或模拟真人行为规避平台风控；正式公开前核实有效平台规则并完成真人账号验收。
- 不部署、合并或扩大公开分发，直到本计划验收、远端 CI、维护者批准、平台授权边界和真实账号验收都通过。

---

## 文件与职责地图

- `backend/src/main/java/com/maple/ai/job/hunting/service/biz/MessageTriageService.java`：新增服务端 HR 消息结构化分类。
- `backend/src/main/java/com/maple/ai/job/hunting/model/vo/MessageTriageResultVO.java`：分类类别、动作、理由和澄清问题。
- `backend/src/main/java/com/maple/ai/job/hunting/model/param/JobSeekerClonedParam.java`：携带经过白名单校验的 BOSS 消息类型元数据。
- `backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedService.java`：按分类动作进入回复、静默、岗位阻断、本人确认或技术失败。
- `backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobFilterService.java`、`backend/src/main/java/com/maple/ai/job/hunting/consts/AIPromptStrConstant.java`：岗位动作契约及风险/不完整信息判断。
- `backend/src/main/java/com/maple/ai/job/hunting/model/entity/PreferenceEntity.java`、`backend/src/main/java/com/maple/ai/job/hunting/utils/PreferenceDefaults.java`：用户偏好数据和兼容默认值。
- `frontend/src/platform/bossPlatform.ts`、`frontend/src/platform/marketingGate.ts`：消息采集和过滤职责；删除浏览器端营销语义决策，保留协议/发送者/重复/坐席状态判断。
- `frontend/src/platform/filterGate.ts`：投递结果动作闸门。
- `frontend/src/components/ui/Preference.vue`、`frontend/src/stores/types.ts`、`frontend/src/stores/preferenceDefaults.ts`：偏好编辑、字段帮助和类型定义。
- `frontend/src/webSocket/messageBody.ts`、`frontend/src/webSocket/packetMessage.ts`：协议元数据到消息类型的安全提取。
- `frontend/tests/marketingGate.test.ts`、`frontend/tests/conversationPreflight.test.ts`、`frontend/tests/messageBody.test.ts` 及相应后端测试：端到端决策边界回归。
- `docs/USER_GUIDE.md`、`docs/ACCEPTANCE.md`、`README.md`、`install.html`、`docs/PROJECT_STATUS_2026-10-14.md`：用户说明、验收步骤和实际发布状态。

---

### Task 0: 核实候选基线与保护现有差异

**Files:** None. Read-only preflight.

- [ ] **Step 1: 确认当前目录、分支、HEAD、远端和工作区差异**

Run from the selected worktree:

```powershell
git rev-parse --show-toplevel
git status --short --branch
git rev-parse HEAD
git remote -v
git diff --stat
git diff -- frontend/src/platform/bossPlatform.ts frontend/src/platform/aiSeatDelivery.ts frontend/tests/adversarialPreRelease.test.ts frontend/tests/aiSeatDelivery.test.ts backend/src/test/java/com/maple/ai/job/hunting/service/biz/MsgSessionServiceKeyTest.java
```

Expected: Record the exact baseline and all pre-existing local changes. Do not run `git checkout`, `git restore`, `reset`, or delete a worktree.

- [ ] **Step 2: Compare the candidate PR and release branch with local HEAD**

Use the configured GitHub integration or `gh` only after checking its current authenticated identity. Read remote metadata without printing credentials. Record whether PR #2 is still current, open, and aimed at the protected release branch.

Expected: A written release-base decision; if the PR head or target changed, stop and rebase the plan against the current approved base before editing.

- [ ] **Step 3: Re-run the existing candidate's narrow baseline tests**

Run the frontend AI-seat delivery and adversarial recovery tests and the backend session/marketing tests before implementation.

Expected: Existing candidate behavior is separated from new regressions. Any baseline failure is investigated before implementation.

### Task 1: Define strict decision contracts with failure-first tests

**Files:**
- Create: `backend/src/main/java/com/maple/ai/job/hunting/model/vo/MessageTriageResultVO.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/model/vo/AiFilterResultVO.java`
- Test: `backend/src/test/java/com/maple/ai/job/hunting/service/biz/MessageTriageServiceTest.java`
- Test: `backend/src/test/java/com/maple/ai/job/hunting/service/biz/JobFilterServicePromptBoundaryTest.java`
- Test: `frontend/tests/filterGate.test.ts`

**Interfaces:**
- `MessageTriageResultVO.Category`: `NORMAL_JOB`, `PROMOTION`, `HIGH_RISK`, `PLATFORM_EVENT`, `UNCERTAIN`, `PROCESSING_ERROR`.
- `MessageTriageResultVO.Action`: `REPLY`, `SILENT_CURRENT_MESSAGE`, `BLOCK_CURRENT_JOB`, `NEEDS_USER_CONFIRMATION`, `NO_ACTION_RETRY`.
- Result fields: `category`, `action`, `reasonCode`, `plainLanguageReason`, `clarificationQuestions`, `evidence`.
- Job filtering returns `DELIVER` or `FILTER`; transport/model errors are represented by the service/API error path, not by `HUMAN_REVIEW`.

- [ ] **Step 1: Add table-driven server tests for message categories**

Use fixtures with these expected results:

```java
assertEquals(NORMAL_JOB, classify("我们招聘抖音运营专员，负责短视频账号内容和数据复盘"));
assertEquals(PROMOTION, classify("加微信领取抖音运营课程资料，报名后安排项目"));
assertEquals(HIGH_RISK, classify("先交培训费才能入职，之后按拉来的人数返佣"));
assertEquals(UNCERTAIN, classify("岗位需要做账号运营，具体工作方式可以再沟通"));
assertEquals(PLATFORM_EVENT, classifyWithBodyType("简历已发送", 12));
```

Add negative examples where “收费” means customer-service duties, “账号” means ordinary account operations, and “返佣” is only quoted as an explicit prohibition. A single word must not decide the category.

Define the test helper in `MessageTriageServiceTest` as:

```java
private Category classify(String message) {
    return service.classify(message, "招聘抖音运营", "候选人简历文本", preference, 1)
            .getCategory();
}

private Category classifyWithBodyType(String message, int bodyType) {
    return service.classify(message, "招聘岗位", "候选人简历文本", preference, bodyType)
            .getCategory();
}
```

- [ ] **Step 2: Add failure-first job action tests**

Assert:

```java
assertEquals("DELIVER", filterDecision("岗位地点未写明，其他职责与简历方向相符").getRecommendedAction());
assertEquals("FILTER", filterDecision("先交培训费才能入职").getRecommendedAction());
assertEquals("FILTER", filterDecision("岗位地点为杭州；用户明确只接受上海").getRecommendedAction());
assertThrows(ApplicationException.class, this::filterDecisionWithMalformedOrUnavailableModel);
```

`ApplicationException` is the existing backend transport error type. The test must also assert its message is the stable retryable text `岗位分析暂时不可用，请稍后重试`, without exposing provider details.

Also assert that the legacy action `CHAT_FIRST` normalizes to `DELIVER` with non-empty `clarificationQuestions`, while `HUMAN_REVIEW` is not emitted by new server responses.

`filterDecision(String jobDescription)` builds an `AIFilterParam` with that exact description, the user's stored preference fixture, and the recording AI facade. `filterDecisionWithMalformedOrUnavailableModel()` supplies either an empty response or a facade that throws; both must produce the same typed processing failure.

- [ ] **Step 3: Add frontend action tests**

Assert that `DELIVER` proceeds, `FILTER` skips, and `PROCESSING_ERROR` skips with a retryable user-facing status. Add a regression that an incomplete low-risk result does not get blocked solely because it has clarification questions.

- [ ] **Step 4: Run the focused tests and confirm the new cases fail**

Run the new Java and TypeScript test files only.

Expected: Failures identify missing/old behavior, not compilation problems in unrelated modules.

### Task 2: Build the server-authoritative HR message classifier

**Files:**
- Create: `backend/src/main/java/com/maple/ai/job/hunting/service/biz/MessageTriageService.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/consts/AIPromptStrConstant.java`
- Test: `backend/src/test/java/com/maple/ai/job/hunting/service/biz/MessageTriageServiceTest.java`

**Interfaces:**
- `MessageTriageService.classify(String question, String jobContext, String resumeContent, PreferenceEntity preference, Integer platformBodyType): MessageTriageResultVO`
- The service uses the existing server-side `AIServiceFacade`; it never returns raw model output to the browser.
- Strict parsing accepts only the declared enums and bounded text/list fields. Empty, malformed, unsupported, or timed-out model output becomes `PROCESSING_ERROR` / `NO_ACTION_RETRY`.

- [ ] **Step 1: Add tests for strict JSON parsing and invalid-output handling**

Cover valid JSON, extra prose around JSON, missing category, unknown action, empty response, timeout/exception, and oversized evidence. Invalid output must not become `NORMAL_JOB`.

The classifier bypasses the model for verified protocol metadata before calling `AIServiceFacade`: body types `4`, `8`, and `12` return `PLATFORM_EVENT`; body type `14` returns `NEEDS_USER_CONFIRMATION`; body type `1` uses AI classification. Unknown body types use AI classification rather than being silently discarded.

- [ ] **Step 2: Add prompt-boundary tests**

Assert that the prompt:

- labels HR message, BOSS job details, resume, and user preferences as external data, not instructions;
- requires intent/action classification using full context rather than standalone keyword matching;
- treats missing job facts as `UNCERTAIN` and recommends asking HR, not `HIGH_RISK`;
- treats ordinary Douyin/short-video/marketing/sales job duties as potentially normal work;
- distinguishes candidate-directed course/group/referral funnels from job duties;
- never invents a salary, city, contact account, resume fact, or candidate consent;
- requires user confirmation before accepting a concrete interview date/time or making an explicit offer/contract commitment.

- [ ] **Step 3: Implement the minimum classifier**

Build a dedicated prompt and strict parser. Bound the message, job context, resume, and preference independently. Do not log their contents. Return only the typed result and sanitized reason.

- [ ] **Step 4: Run the classifier tests**

Run `mvn -o -Dtest=MessageTriageServiceTest test` from `backend`.

Expected: all category, prompt-boundary, malformed-response, and failure-path tests pass.

### Task 3: Integrate message decisions and narrow confirmation pauses

**Files:**
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/model/param/JobSeekerClonedParam.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedService.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/model/vo/JobSeekerClonedResultVO.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/utils/ConversationGateUtil.java`
- Test: `backend/src/test/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedServiceMarketingTest.java`
- Test: `backend/src/test/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedServiceHumanHandoffTest.java`
- Test: `backend/src/test/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedServiceReplyGuardTest.java`

**Interfaces:**
- Add optional `platformBodyType` to `JobSeekerClonedParam`; accept only known supported values and never infer it from message wording.
- `JobSeekerClonedService.ask` calls the classifier after authentication, duplicate/session checks, and reliable protocol-event filtering, but before `sessionChat`.
- Result stop reasons include `PROMOTION_SILENT`, `HIGH_RISK_JOB`, `USER_CONFIRMATION_REQUIRED`, and `AI_PROCESSING_ERROR`.

- [ ] **Step 1: Test the no-pause preference cases**

For HR messages asking for WeChat, stating `6K`, or giving a city:

- with a matching explicit user preference, assert the AI chat path is allowed;
- with no preference, assert the classifier may ask HR for details but does not return `USER_CONFIRMATION_REQUIRED`;
- assert the service never fabricates or leaks a contact value absent from the user's authorized profile/preferences.

- [ ] **Step 2: Test the narrow appointment/commitment pause**

Assert that a concrete “明天上午十点来公司面试可以吗” returns `USER_CONFIRMATION_REQUIRED` and pauses only that `jobKey`; other job sessions and the account-level seat remain enabled.

Assert that “合同期限是三年” by itself does not pause. A direct request to accept/sign must not be accepted automatically.

- [ ] **Step 3: Test promotion, high risk, uncertainty, and provider failure**

Assert that default-off promotion returns `PROMOTION_SILENT` without `sessionChat`; ordinary uncertain job talk continues into chat; a high-risk job returns `HIGH_RISK_JOB` and blocks only that job; AI provider/JSON failure returns `AI_PROCESSING_ERROR` without sending.

- [ ] **Step 4: Narrow deterministic conversation gates**

Keep deterministic checks only for authenticated session state, duplicate IDs, explicit user refusal, reliable structured BOSS event metadata, and clear protocol noise. Remove broad contact/salary/location keyword pauses from the free-text path; route those through the server classifier and user preferences.

- [ ] **Step 5: Run focused backend tests**

Run the four service test classes listed above plus `ConversationGateUtilTest`.

Expected: the new action semantics pass without changing account-level AI-seat or unrelated session state.

### Task 4: Make the browser a message transport and executor

**Files:**
- Modify: `frontend/src/platform/bossPlatform.ts`
- Modify: `frontend/src/platform/marketingGate.ts`
- Modify: `frontend/src/platform/replyDisposition.ts`
- Modify: `frontend/src/platform/aiPower.ts`
- Modify: `frontend/src/webSocket/messageBody.ts`
- Modify: `frontend/src/webSocket/packetMessage.ts`
- Test: `frontend/tests/marketingGate.test.ts`
- Test: `frontend/tests/messageBody.test.ts`
- Test: `frontend/tests/adversarialPreRelease.test.ts`

**Interfaces:**
- Browser sends `platformBodyType` with the current `/api/job/seeker/cloned/ask` request.
- Browser-side `marketingGate` no longer makes the final semantic blocking decision; the server result controls `PROMOTION_SILENT` and `HIGH_RISK_JOB`.
- Browser-side protocol filters use known metadata (`body.type`, sender identity, message id), not text such as “系统”“抖音”“收费”.

- [ ] **Step 1: Add message metadata tests**

Verify body types 4, 8, and 12 are treated as protocol/system events in the same manner as current behavior; verify normal type-1 HR text containing “系统通知”“抖音”“账号” remains eligible for server classification; verify body type 14 is treated as an interview card requiring the appropriate confirmation flow.

- [ ] **Step 2: Add regression tests proving browser words do not silence messages**

Assert that the browser no longer drops “招聘抖音运营专员” or a message containing “收费客户的账号运营” before the server request. Assert that the browser still suppresses reliable protocol-only events and candidate-authored messages.

- [ ] **Step 3: Remove the duplicate browser semantic classifier**

Remove or reduce `marketingGate.ts` to non-semantic protocol helpers. In `processMergedMessages`, preserve merge, sender, duplicate, account/device seat, and send-boundary checks, then call `AiPower.ask`.

- [ ] **Step 4: Handle server actions visibly**

Map `PROMOTION_SILENT` to completed-current-message/no-session-pause; map `HIGH_RISK_JOB` to current-job blocked state; map `USER_CONFIRMATION_REQUIRED` to a clear current-session pause notice; map `AI_PROCESSING_ERROR` to a retryable error without marking the message successfully answered.

- [ ] **Step 5: Remove silent truncation before semantic classification**

Keep the existing 5000-character server request ceiling for this release, but never silently cut a merged batch and classify its prefix. If the complete normalized batch exceeds 5000 characters, return an explicit `AI_PROCESSING_ERROR` state, retain it as a user-visible unresolved item that automatic unread recovery does not repeatedly retry, and show “消息内容较长，未自动回复；请打开会话查看”。 Do not mark it as a successfully answered message. Revisit the internal limit separately using measured model context and real-user evidence.

- [ ] **Step 6: Run focused frontend tests**

Run `pnpm test -- --test-name-pattern="marketing|message body|reply disposition|adversarial"`.

Expected: ordinary text reaches server classification; structured events and sender/session safety remain enforced.

### Task 5: Improve preference defaults and user editing

**Files:**
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/model/entity/PreferenceEntity.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/utils/PreferenceDefaults.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobFilterService.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/service/biz/MessageTriageService.java`
- Modify: `frontend/src/stores/types.ts`
- Modify: `frontend/src/stores/preferenceDefaults.ts`
- Modify: `frontend/src/components/ui/Preference.vue`
- Test: `backend/src/test/java/com/maple/ai/job/hunting/utils/PreferenceDefaultsTest.java`
- Test: `frontend/tests/preferenceDefaults.test.ts`
- Test: `frontend/scripts/settings-clarity.smoke.mjs`

**Interfaces:**
- Add optional editable `promotionReplyPolicy` to the existing JSON preference object.
- Keep `jobGoal`, `unacceptableConditions`, `clarifyConditions`, `communicationPreferences`, and `marketingReplyE`.
- The default policy says ordinary promotional messages are silent unless allowed; normal Douyin/short-video/market/sales job duties are not filtered by words alone.

- [ ] **Step 1: Write preservation and migration tests**

Test first initialization, empty legacy fields, fields exactly equal to old defaults, and user-edited fields. New defaults may fill empty or exact-old-default values only; custom text must remain byte-for-byte unchanged. `marketingReplyE` remains false when absent.

- [ ] **Step 2: Write user-facing settings smoke tests**

Assert the labels and examples ask understandable questions about salary floor, desired city/work mode, WeChat/contact sharing, interview availability, contract/overtime/travel constraints, and promotion types. Assert sample answers are help text only and are not inserted into saved user facts.

- [ ] **Step 3: Implement one-time editable defaults**

Use a new preference-default version marker. Preserve existing custom values; migrate only empty values and exact known previous defaults. Keep persistence through the existing JSON field; add no SQL migration.

- [ ] **Step 4: Update the preference form**

Use clear field names: “我想找什么工作”, “哪些情况我不能接受”, “信息不全时优先问什么”, “跟 HR 沟通时按什么方式”, and “我不希望 AI 回复哪些推广”. Prefill generic, editable rules from the spec. Put example questions beside fields without treating examples as candidate facts.

- [ ] **Step 5: Run preference tests and smoke checks**

Run `pnpm test -- --test-name-pattern="preference defaults"` and `pnpm verify:prelaunch`.

Expected: old customized preferences survive upgrade; new accounts receive editable defaults; UI copy does not claim to know the user's salary or city.

### Task 6: Align job screening with “incomplete means continue”

**Files:**
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobFilterService.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/consts/AIPromptStrConstant.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/model/vo/AiFilterResultVO.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedService.java`
- Modify: `frontend/src/platform/filterGate.ts`
- Modify: `frontend/src/platform/platform.ts`
- Test: `backend/src/test/java/com/maple/ai/job/hunting/service/biz/JobFilterServicePromptBoundaryTest.java`
- Test: `backend/src/test/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedServiceReplyGuardTest.java`
- Test: `frontend/tests/filterGate.test.ts`

- [ ] **Step 1: Write model and action contract regressions**

Cover low-risk missing salary/location/company details => `DELIVER` with clarification questions; explicit user-condition conflict => `FILTER`; contextual high-risk payment/recruitment scheme => `FILTER`; benign job duties mentioning Douyin, account operations, customer payment handling, or commission in a non-risk context => not filtered solely by term.

- [ ] **Step 2: Write technical-failure tests**

For empty response, malformed JSON, missing required decision fields, model exception, and timeout, assert the automatic action is skipped and the caller gets an explicit retryable processing error. Do not map these cases to `HUMAN_REVIEW` or `DELIVER`.

- [ ] **Step 3: Simplify the job screening prompt and normalizer**

New model output uses `DELIVER` or `FILTER`, `reason`, `riskSignals`, and `clarificationQuestions`. Treat old `CHAT_FIRST` as `DELIVER` with clarification questions. Reject invalid output as a processing failure, not a medium-risk job.

- [ ] **Step 4: Align frontend delivery gates**

`shouldBlockAfterAiFilter` blocks explicit `FILTER` and processing failures; it does not block a valid `DELIVER` result because information is incomplete. Ensure the job loop catches a processing error and skips only the current job, leaving later jobs and the user's account usable.

- [ ] **Step 5: Run focused job-filter tests**

Run `mvn -o -Dtest=JobFilterServicePromptBoundaryTest,JobSeekerClonedServiceReplyGuardTest test` and `pnpm test -- --test-name-pattern="filter gate"`.

Expected: incomplete information proceeds; explicit mismatch/risk blocks; AI infrastructure failure never masquerades as a content decision.

### Task 7: Synchronize documentation and acceptance cases

**Files:**
- Modify: `README.md`
- Modify: `install.html`
- Modify: `docs/USER_GUIDE.md`
- Modify: `frontend/src/assets/UseDocument.md`
- Modify: `docs/ACCEPTANCE.md`
- Modify: `docs/DATA_FLOW_AND_RETENTION.md`
- Modify: `docs/PROJECT_STATUS_2026-10-14.md`
- Modify: `docs/PROJECT_PROGRESS.md`

- [ ] **Step 1: Update plain-language behavior descriptions**

Explain that the browser reads BOSS messages and the server AI interprets them; normal incomplete job details do not automatically stop delivery; ordinary promotion is silent by default; salary/location/contact follow saved user preferences; a specific interview time is left for the user to confirm.

- [ ] **Step 2: Explain BOSS system events**

State that system notifications/cards can appear in the conversation view but are not HR messages. The script identifies known structured events, not ordinary text containing a keyword.

- [ ] **Step 3: Add acceptance scenarios**

Add the exact cases from the spec: normal Douyin operator job, course/group funnel, explicit upfront fee, incomplete listing, salary/city preferences, missing preferences, concrete interview invitation, contract-details inquiry, protocol event, model failure, and custom preference persistence.

- [ ] **Step 4: Reconcile status claims**

Update the status documents only with freshly verified local/remote evidence. Keep “local tests”, “remote CI”, “maintainer-approved”, “real BOSS account accepted”, and “deployed” as distinct states.

### Task 8: Full verification and independent adversarial review

**Files:** No new production scope; inspect all planned changes.

- [ ] **Step 1: Run complete frontend checks**

```powershell
pnpm test
pnpm build
pnpm verify:prelaunch
pnpm verify:public
```

- [ ] **Step 2: Run complete backend checks**

```powershell
mvn -o test
mvn -o -DskipTests package
```

- [ ] **Step 3: Verify artifacts and secret boundaries**

Run `git diff --check`, verify the root userscript matches the freshly built `frontend/dist` artifact, and scan tracked files for credential patterns without printing any secret values. Confirm logs and fixtures contain no real HR chat, resume, phone, or email.

- [ ] **Step 4: Run the adversarial test review**

Use `adversarial-test-review` after normal verification. Challenge:

- legitimate Douyin/marketing/sales jobs mislabeled as promotion;
- course funnel hidden after normal job text;
- explicit upfront payment described without the exact word “押金”;
- negation followed by a new risk statement;
- user preference prompt injection or attempts to override safety;
- preference migration overwriting a customized value;
- one device's paused session versus another session;
- message tail beyond the previous 5000-character cut;
- model/API outage treated as “safe to deliver”;
- HR's ordinary text containing “系统通知” dropped as a protocol event;
- repeated BOSS structured cards causing a duplicate reply.

Create a synthetic offline regression set with at least 20 Chinese cases: benign job duties, explicit recruiting funnels, high-risk actions, negated risk statements, user-preference conflicts, incomplete job cards, protocol events, and technical failures. Require every named acceptance fixture in the spec to pass; record false-positive and false-negative cases separately. Do not claim broad classification accuracy from prompt inspection or mocked model responses.

Record each finding with a reproducible test or source path; fix high/critical findings before release.

### Task 9: Publish candidate and deploy only through protected release flow

**Files/Services:** release branch, GitHub PR, OVH app artifact and static userscript/install page.

- [ ] **Step 1: Re-check platform authorization and real-account test scope**

Verify current BOSS rules for browser scripts and automated messages from the official current source. Do not treat missing public wording as permission. Complete a controlled real-account test covering login, resume, one low-risk delivery, HR chat classification, user preference behavior, interview pause, stop/resume, and reconnect.

Expected: If the authorization boundary or any high-risk behavior remains unresolved, stop before broad public distribution.

- [ ] **Step 2: Push only the reviewed feature branch and update the PR**

Confirm no user/unrelated files enter the commit. Do not push `main`. Require required GitHub CI, CodeQL, maintainer approval, and protected-branch checks.

- [ ] **Step 3: Deploy atomically after approval**

Back up the existing JAR, userscript, and install page; upload to staging; verify hashes; atomically replace only the app and static artifacts; restart only `ai-job.service`. Leave Nginx, MariaDB, Redis, and unrelated services untouched.

- [ ] **Step 4: Verify live behavior and rollback**

Check authenticated service health, downloaded userscript/install hashes, first login, default preference display, one controlled job-screen result, one ordinary chat, one promotion classification, and one interview-confirmation pause. If any check fails, restore the backed-up artifacts and restart only `ai-job.service`.

## Deferred After the Initial Controlled Release

These items have separate data, product, or scheduling boundaries and must not be silently folded into this implementation:

1. Semantic validation that an uploaded file resembles a resume. Current 80-character extractability is not resume validation. Plan a separate AI/file-validation design; do not claim truth or completeness verification.
2. Structured interview calendar, conflict detection, timezone, travel buffer, and cross-device calendar synchronization.
3. Anonymous classification-quality metrics and user feedback collection after privacy and consent review.
4. Model-context budget tuning. Keep `5000` as an internal technical limit only until a measured alternative is selected; do not present it as normal message length.
