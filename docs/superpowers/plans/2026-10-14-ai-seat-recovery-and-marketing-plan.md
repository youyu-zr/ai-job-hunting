# AI 坐席断线接续与营销消息处理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完善 AI 坐席总开关、断线后接续未回复消息和营销推广默认静默规则，并完成验证、审查、推送与可回滚部署。

**Architecture:** 保留现有浏览器未读补扫和服务端会话闸门。新增服务端可解释的营销消息分类和偏好开关；前端只负责恢复扫描、发送者识别和用户说明，服务端在调用模型前最终决定静默、人工接管或继续回复。

**Tech Stack:** Vue 3、TypeScript、Node test runner、Spring Boot、Java 17、JUnit 5、Maven、pnpm、Nginx、systemd。

**Spec:** `docs/superpowers/specs/2026-10-14-ai-seat-recovery-and-marketing-design.md`

## Global Constraints

- 不修改 `main`，先在隔离分支开发，验证后合并到 `release/open-source-20261011`。
- 不把营销识别写成单个关键词过滤；必须有完整语境组合和反例测试。
- 不在 AI 坐席关闭时处理实时或积压消息。
- 用户手动回复只暂停当前岗位会话。
- 不执行数据库结构迁移；新增偏好字段使用现有 JSON 配置兼容方式。
- 生产部署前备份 JAR、userscript 和安装页，失败自动回滚。
- 正式公开分发前核对 BOSS 当前有效协议和第三方脚本/自动收发消息授权边界；宣传不承诺算法推荐效果或账号安全。
- 不输出或提交 API Key、Cookie、数据库密码、简历和真实聊天内容。

---

### Task 1: 服务端营销消息闸门

**Files:**
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/utils/ConversationGateUtil.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/model/entity/PreferenceEntity.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedService.java`
- Modify: `backend/src/main/java/com/maple/ai/job/hunting/model/vo/JobSeekerClonedResultVO.java` only if a readable stop reason needs documenting
- Test: `backend/src/test/java/com/maple/ai/job/hunting/utils/ConversationGateUtilTest.java`
- Test: `backend/src/test/java/com/maple/ai/job/hunting/service/biz/JobSeekerClonedServiceHumanHandoffTest.java` or a new focused test beside it

**Interfaces:**
- Add `ConversationGateUtil.isPromotionalMessage(String question): boolean`.
- Add `PreferenceEntity.marketingReplyE` with default Java `false`.
- Add a service-side check before AI model invocation that returns `STOP` with `MARKETING_SILENT` when the preference is disabled and the complete message matches promotional CTA semantics.
- Extend terminal stop handling tests so `MARKETING_SILENT` is not retried by the browser scanner.

- [ ] **Step 1: Write failing conversation classification tests**

Add tests for:

```java
assertTrue(ConversationGateUtil.isPromotionalMessage("扫码进群领取资料，注册后安排推广任务"));
assertTrue(ConversationGateUtil.isPromotionalMessage("先充值成为代理，邀请好友返佣"));
assertFalse(ConversationGateUtil.isPromotionalMessage("我们招聘市场推广专员，负责品牌内容和活动执行"));
assertFalse(ConversationGateUtil.isPromotionalMessage("岗位负责推广公司产品，正常底薪加绩效，不要求付费"));
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run from `backend`:

```powershell
mvn -o -Dtest=ConversationGateUtilTest test
```

Expected: FAIL because `isPromotionalMessage` is not yet present or does not satisfy the new cases.

- [ ] **Step 3: Implement the smallest explainable classifier**

Use normalized text and require a promotion action or funnel action plus promotion context. Keep hard-risk terms such as payment, recharge, pyramid referral, account trading and刷单 included. Do not classify ordinary job descriptions solely because they contain `推广`, `销售`, `市场`, or `运营`.

- [ ] **Step 4: Add the preference-aware service test**

Create a service test with a mocked `UserInfoMapper` preference where `marketingReplyE` is `false`, send a promotional message, and assert:

```java
assertEquals("MARKETING_SILENT", result.getStopReason());
assertEquals(0, ai.sessionChatCalls.get());
```

Add a second case with `marketingReplyE=true` and a non-hard-risk promotional message; assert the model path remains eligible.

- [x] **Adversarial follow-up: tighten hard-risk boundaries**

  - Added regression coverage for negation scope followed by `另有`;
  - Removed standalone `充值` as a hard-risk trigger;
  - Required an explicit candidate-directed funnel action and added job-duty context counterexamples;
  - Focused tests: `ConversationGateUtilTest`, `JobRiskKeywordUtilTest`, and `JobSeekerClonedServiceMarketingTest`.

- [x] **Step 5: Run focused backend tests**

```powershell
mvn -o -Dtest=ConversationGateUtilTest,JobSeekerClonedServiceHumanHandoffTest test
```

Expected: PASS with zero failures.

### Task 2: Frontend preference and terminal stop behavior

**Files:**
- Modify: `frontend/src/stores/types.ts`
- Modify: `frontend/src/stores/remote.ts`
- Modify: `frontend/src/components/ui/Preference.vue`
- Modify: `frontend/src/platform/replyDisposition.ts`
- Test: `frontend/tests/replyDisposition.test.ts`
- Test: `frontend/tests/scopedReadiness.test.ts` only if preference default propagation needs coverage

**Interfaces:**
- Add optional `marketingReplyE?: boolean` to `PreferenceConfig`.
- Default it to `false` when the server does not send it.
- Show a plain-language checkbox: `营销推广类消息自动回复（默认关闭）`.
- Treat `MARKETING_SILENT` as a terminal stop that can be marked completed and must not be retried by unread compensation.

- [ ] **Step 1: Write failing frontend tests**

Add a `replyDisposition` case:

```ts
assert.equal(shouldCompleteStoppedReply({stopReason: 'MARKETING_SILENT'}), true);
```

Add a source-level smoke assertion that the preference default is `false` and the label contains `营销推广类消息自动回复`.

- [ ] **Step 2: Run the focused frontend tests and verify the new assertions fail**

```powershell
pnpm test -- --test-name-pattern="terminal|marketing|preference"
```

Expected: the new assertion fails before implementation.

- [ ] **Step 3: Implement the preference and UI**

Use the existing preference merge/default patterns. The checkbox must explain that turning it on does not bypass hard-risk or human-confirmation rules.

- [ ] **Step 4: Run focused frontend tests**

```powershell
pnpm test -- --test-name-pattern="terminal|marketing|preference"
```

Expected: PASS.

### Task 3: Correct断线恢复的最后发送者判断

**Files:**
- Create: `frontend/src/platform/pendingConversation.ts`
- Modify: `frontend/src/platform/bossPlatform.ts`
- Test: `frontend/tests/pendingConversation.test.ts`

**Interfaces:**
- Export `shouldTreatPendingConversationAsReplyable(input): boolean`, where input contains `unread`, `preview`, `lastMessageSenderId`, `currentUserId`, `selected`, and `latestSender`.
- If `lastMessageSenderId === currentUserId`, return `false` before unread-marker handling.
- If sender metadata is absent, only use the selected conversation DOM fallback.
- Preserve current behavior for an unread HR preview and selected conversation whose latest visible sender is HR.

- [ ] **Step 1: Write failing pure tests**

Cover:

```ts
assert.equal(shouldTreatPendingConversationAsReplyable({
  unread: true, preview: '你好，方便沟通吗？',
  lastMessageSenderId: 100, currentUserId: 100,
  selected: false, latestSender: 'unknown',
}), false);

assert.equal(shouldTreatPendingConversationAsReplyable({
  unread: true, preview: '你好，方便沟通吗？',
  lastMessageSenderId: 200, currentUserId: 100,
  selected: false, latestSender: 'unknown',
}), true);

assert.equal(shouldTreatPendingConversationAsReplyable({
  unread: false, preview: '你好',
  lastMessageSenderId: null, currentUserId: 100,
  selected: true, latestSender: 'boss',
}), true);
```

- [ ] **Step 2: Run the focused test and verify it fails**

```powershell
pnpm test -- --test-name-pattern="pending conversation"
```

Expected: FAIL because the helper does not yet exist.

- [ ] **Step 3: Implement the helper and wire it into `resolvePendingConversation`**

Keep the existing 15-second single-contact scan and 24-hour completed fingerprint. Only change the order and decision boundary so a candidate-sent last message cannot be mistaken for an HR pending message.

- [ ] **Step 4: Run focused frontend tests**

```powershell
pnpm test -- --test-name-pattern="pending conversation|conversation pause|reply disposition"
```

Expected: PASS.

### Task 4: User-facing wording and release documentation

**Files:**
- Modify: `install.html`
- Modify: `README.md`
- Modify: `docs/USER_GUIDE.md`
- Modify: `frontend/src/assets/UseDocument.md`
- Modify: `docs/ACCEPTANCE.md`
- Modify: `docs/DATA_FLOW_AND_RETENTION.md` to remove stale `0.0.115` wording if it remains in the current path

- [ ] **Step 1: Rewrite the installation page into three user steps**

Use:

1. 安装 ScriptCat（脚本猫）或已有的 Tampermonkey（二选一）；
2. 安装 AI 工作猎手脚本；
3. 登录 BOSS、打开职位或消息页并刷新。

Keep developer/release details below the user flow. Explain that ordinary users do not provide an API Key.

- [ ] **Step 2: Replace ambiguous message behavior**

Use the terms:

- `AI 坐席总开关：账号级开关`;
- `暂停本会话：只暂停当前 HR`;
- `恢复本会话自动回复：恢复当前 HR 的实时消息和符合条件的断线未回复消息`;
- `营销推广类消息默认不自动回复`;
- `用户手动回复后，当前会话暂停，其他会话不受影响`.

- [ ] **Step 3: Update acceptance cases**

Add explicit cases for:

- reconnect with HR-last-message;
- reconnect with candidate-last-message;
- reconnect with marketing CTA;
- global seat off during backlog;
- per-conversation pause during backlog.

- [ ] **Step 4: Run text/smoke checks**

```powershell
pnpm verify:prelaunch
pnpm verify:public
git diff --check
```

Expected: PASS.

### Task 5: Full verification and release preparation

**Files:**
- Modify: `docs/PROJECT_STATUS_2026-10-14.md`
- Modify: `docs/PROJECT_PROGRESS.md`
- Modify: `README.md` version/status lines as needed

- [ ] **Step 1: Run the complete frontend verification**

```powershell
pnpm test
pnpm build
pnpm verify:prelaunch
pnpm verify:public
```

- [ ] **Step 2: Run the complete backend verification**

```powershell
mvn -o test
mvn -o -DskipTests package
```

- [ ] **Step 3: Inspect the final diff and scan for secrets**

Check:

```powershell
git diff --check
rg -n -i "sk-[A-Za-z0-9]|AIza|Cookie:|password=|private_key|BEGIN .* PRIVATE KEY" -- . -g '!frontend/node_modules/**' -g '!backend/target/**'
```

The scan must not reveal real credentials.

- [ ] **Step 4: Run adversarial release review**

Review:

- marketing false positives;
- negative-scope boundary and standalone recharge false positives;
- marketing hard-risk bypass when the preference is enabled;
- routine job duties containing scan/check-in/customer registration;
- candidate-last-message after mobile reply;
- global seat off with unread scan active;
- paused session with reconnect;
- duplicate recovery after a successful reply;
- version/documentation mismatch.

The 2026-10-14 local review found and fixed the above marketing-boundary cases. Current platform authorization for third-party userscripts and automated messaging remains an external release gate.

- [ ] **Step 5: Commit the candidate**

```powershell
git add backend frontend install.html README.md docs
git commit -m "feat: recover pending HR messages and silence promotions"
```

### Task 6: Integrate, push, deploy and verify

**Files/Services:**
- Git branch `release/open-source-20261011`
- GitHub PR #1
- OVH `ai-job.service`, static install page, userscript

- [ ] **Step 1: Review the candidate commit and fast-forward the release branch**

Verify the worktree is clean and the candidate commit contains only the planned changes. Remove the temporary worktree only after the release branch has been advanced.

- [ ] **Step 2: Push the release branch**

Push only `release/open-source-20261011`; do not push directly to `main` and do not merge PR #1 automatically.

- [ ] **Step 3: Run the repository CI checks**

Wait for frontend/backend checks and CodeQL. If CI fails, stop and fix before deployment.

- [ ] **Step 4: Build deploy artifacts and record SHA-256**

Record JAR, userscript, and install page hashes before upload.

- [ ] **Step 5: Backup and deploy atomically**

On OVH:

1. back up current JAR, userscript, and install page;
2. upload to staging;
3. verify staging hashes;
4. atomically replace the three files;
5. restart only `ai-job.service`;
6. leave Nginx, MariaDB, and Redis running;
7. run the health/download checks.

- [ ] **Step 6: Roll back automatically if health or hash checks fail**

Restore all three previous files and restart only `ai-job.service`. Do not leave a mixed frontend/backend version online.

- [ ] **Step 7: Final report**

Report exact deployed version, hashes, services checked, tests, CI state, deployment evidence, remaining real-BOSS acceptance items, and the fact that the BOSS recommendation/anti-abuse algorithm is not publicly verified.
