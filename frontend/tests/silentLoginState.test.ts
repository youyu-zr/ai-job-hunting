import assert from "node:assert/strict";
import test from "node:test";
import {readFileSync} from "node:fs";

import {classifySilentLoginResponse} from "../src/utils/silentLoginState.ts";

const ui = (relative: string) => readFileSync(new URL(`../src/${relative}`, import.meta.url), "utf8");

test("an uncreated user is represented as a first-use state", () => {
    assert.equal(
        classifySilentLoginResponse({data: {code: 2000, message: "用户不存在"}}),
        "legacy-registration-required"
    );
});

test("a valid session response is represented as authenticated", () => {
    assert.equal(
        classifySilentLoginResponse({data: {code: 200, data: "session-token"}}),
        "authenticated"
    );
});

test("an invalid login response remains an error", () => {
    assert.throws(
        () => classifySilentLoginResponse({data: {code: 401}}),
        /静默登录返回无效状态/
    );
});

test("first-use state short-circuits profile and activation reads", () => {
    const remote = ui("stores/remote.ts");
    const firstUseStart = remote.indexOf('if (loginOutcome === "legacy-registration-required")');
    const userInfoRequest = remote.indexOf('axios.post("/api/user/userinfo", {})', firstUseStart);
    const firstUseBranch = remote.slice(firstUseStart, remote.indexOf("logging.debug", firstUseStart));

    assert.ok(firstUseStart >= 0);
    assert.ok(userInfoRequest > firstUseStart);
    assert.match(firstUseBranch, /applyPreferenceDefaults/);
    assert.match(firstUseBranch, /return;/);
    assert.doesNotMatch(firstUseBranch, /axios\.(post|get)/);
});

test("automatic account creation waits for live BOSS identity before calling silent login", () => {
    const remote = ui("stores/remote.ts");
    const platform = ui("platform/platform.ts");
    const identityWait = ui("utils/bossIdentity.ts");
    const waitForIdentity = remote.indexOf("await waitForBossIdentity");
    const silentLogin = remote.indexOf("await silentlyLogin(requestedAccountId, identity.token)");

    assert.ok(waitForIdentity >= 0);
    assert.ok(silentLogin > waitForIdentity);
    assert.match(platform, /userRemoteLoad\(\)/);
    assert.match(identityWait, /attempts = 240/);
    assert.match(remote, /identityRetryCount < 3/);
    assert.match(remote, /void userRemoteLoad\(options\)/);
    assert.match(remote, /账号尚未创建/);
    assert.match(remote, /这一步不需要导入简历/);
    assert.doesNotMatch(remote, /\/api\/user\/import\/resume/);
});

test("silent login deduplication distinguishes BOSS token generations", () => {
    const tools = ui("utils/tools.ts");
    assert.match(tools, /Map<string, Map<string, Promise<SilentLoginOutcome>>>/);
    assert.match(tools, /scopedRequests\?\.get\(identity\.token\)/);
    assert.match(tools, /bossToken.*token|token.*bossToken/);
    assert.match(tools, /pageToken === token/);
    assert.match(tools, /clearScopedAuthorization\(sessionScope, token\)/);
});

test("resume import session readiness is bound to the verified BOSS token", () => {
    const remote = ui("stores/remote.ts");
    const aiJob = ui("components/ui/AiJob.vue");
    assert.match(remote, /isApplicationSessionReady\(\s*expectedBossToken/);
    assert.match(aiJob, /isApplicationSessionReady\(token\)/);
});

test("stale BOSS cookie invalidates the current user-config readiness", () => {
    const remote = ui("stores/remote.ts");
    const readyCheck = remote.slice(
        remote.indexOf("export function isUserConfigReady"),
        remote.indexOf("export function isApplicationSessionReady"),
    );
    assert.match(readyCheck, /loginStore\.bossToken === currentBossToken\(\)/);
});

test("changing servers resets the account profile before starting a fresh session", () => {
    const aiJob = ui("components/ui/AiJob.vue");
    assert.match(aiJob, /if \(serverChanged\) \{[\s\S]*?loginStore\.reset\(\);[\s\S]*?userStore\.switchAccount\(accountId, serverStore\.baseUrl\)/);
    assert.match(aiJob, /if \(serverChanged && String\(Tools\.window\?\._PAGE\?\.\uid \?\? ""\)\.trim\(\)\) \{[\s\S]*?void userRemoteLoad\(\)/);
});

test("token polling is bounded and network errors preserve the original error", () => {
    const tools = ui("utils/tools.ts");
    const axios = ui("axios.ts");
    const identityWait = ui("utils/bossIdentity.ts");
    const networkError = axios.match(/if \(error\.code === 'ERR_NETWORK'\) \{[\s\S]*?\n\s{8}\}/)?.[0] || "";

    assert.match(tools, /waitForBossIdentity/);
    assert.match(identityWait, /attempt < attempts/);
    assert.match(identityWait, /intervalMs/);
    assert.match(tools, /getCookieValue\("bst"\)/);
    assert.match(identityWait, /readToken \? readToken\(\) \?\? "" : page\?\.token/);
    assert.match(networkError, /return Promise\.reject\(error\)/);
    assert.doesNotMatch(networkError, /Promise\.reject\(\(\) =>/);
});
