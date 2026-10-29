import assert from "node:assert/strict";
import test from "node:test";
import {readFileSync} from "node:fs";

import {classifySilentLoginResponse, silentLoginRefusalMessage} from "../src/utils/silentLoginState.ts";

const ui = (relative: string) => readFileSync(new URL(`../src/${relative}`, import.meta.url), "utf8");

test("a refused identity keeps the backend's explanation instead of a generic error", () => {
    // The backend distinguishes "this account is not registered" from "the credential was
    // rejected". Replacing both with "静默登录返回无效状态" hides the only actionable part.
    const refusal = {
        data: {code: 2003, message: "身份校验暂不可用，且该 BOSS 账号尚未注册；请联系管理员开通内测权限"},
    };
    assert.equal(classifySilentLoginResponse(refusal), "identity-refused");
    assert.match(silentLoginRefusalMessage(refusal), /尚未注册/);
});

test("a refusal without a message falls back to a usable default", () => {
    assert.equal(silentLoginRefusalMessage({data: {code: 2003}}), "身份校验失败，请确认已登录本人 BOSS 账号后重试");
    assert.equal(silentLoginRefusalMessage({data: {code: 2003, message: "   "}}),
        "身份校验失败，请确认已登录本人 BOSS 账号后重试");
});

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
    // The wait must stay bounded and must not be a minute-long silent poll: a logged-out page is
    // detectable immediately and waiting only makes the panel look frozen.
    assert.match(identityWait, /DEFAULT_ATTEMPTS = 32/);
    assert.match(identityWait, /readLoginState\(\)\?\.isLogin === false/);
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
    // The stored session is compared with the value this attempt was replacing, not with the BOSS
    // token: those live in different namespaces and can never be equal.
    assert.match(tools, /authorizationBeforeAttempt = scopedAuthorization\(sessionScope\)/);
    assert.match(tools, /clearScopedAuthorization\(sessionScope, authorizationBeforeAttempt\)/);
});

test("resume import readiness is bound to the application session, not the BOSS cookie", () => {
    const remote = ui("stores/remote.ts");
    const aiJob = ui("components/ui/AiJob.vue");
    assert.match(remote, /export function isApplicationSessionReady\(\): boolean/);
    assert.match(aiJob, /isApplicationSessionReady\(\)/);
    assert.doesNotMatch(aiJob, /isApplicationSessionReady\(token\)/);
});

test("a rotated BOSS cookie does not invalidate the current user-config readiness", () => {
    const remote = ui("stores/remote.ts");
    const readyCheck = remote.slice(
        remote.indexOf("export function isUserConfigReady"),
        remote.indexOf("export function isApplicationSessionReady"),
    );
    // Readiness answers "did the config load", not "is the cached cookie still current". Treating a
    // cookie rotation as a readiness failure left the panel stuck with no way back short of reload.
    assert.doesNotMatch(readyCheck, /bossToken === currentBossToken\(\)/);
    assert.match(readyCheck, /userConfigReadiness\.isReady\(scope\)/);
});

test("a failed login is retried instead of latching the panel as disconnected", () => {
    const remote = ui("stores/remote.ts");
    assert.match(remote, /function scheduleLoginRetry/);
    assert.match(remote, /Retry-After/);
    assert.match(remote, /LOGIN_RETRY_MAX/);
    assert.match(remote, /void userRemoteLoad\(\)/);
});

test("a settled readiness failure can be retried for the same scope", () => {
    const readiness = ui("stores/scopedReadiness.ts");
    // A completed success is final; a completed failure must not be, or a single transient error
    // pins the account at "not ready" until the page is reloaded.
    assert.match(readiness, /if \(current\.ready\) \{/);
    assert.match(readiness, /state = createState\(scope\);/);
    assert.match(readiness, /invalidate\(scope: string\)/);
});

test("a rate limit or transport failure does not discard a session that was already working", () => {
    const tools = ui("utils/tools.ts");
    const marker = 'logRecorder.error("静默登录失败"';
    const catchBlock = tools.slice(tools.indexOf(marker), tools.indexOf("throw e;", tools.indexOf(marker)));

    // The bug this pins down: the previous version cleared the stored session on EVERY failure,
    // so a 429 from the login rate limiter deleted a perfectly good session and left the panel
    // stuck at "not connected" until the page was reloaded.
    assert.match(catchBlock, /isSessionRejectingFailure\(e\)/);
    assert.match(tools, /function isSessionRejectingFailure/);
});

test("only a credential rejection counts as evidence against the stored session", () => {
    const tools = ui("utils/tools.ts");
    const decision = tools.slice(
        tools.indexOf("function isSessionRejectingFailure"),
        tools.indexOf("export const isProdEnv"),
    );

    // 401/403 and the backend's 2003 mean "this identity is not valid".
    assert.match(decision, /status === 401 \|\| status === 403/);
    assert.match(decision, /code === 2003/);
    // "We could not ask" must never be read as "you are not allowed".
    assert.match(decision, /ERR_NETWORK/);
    assert.match(decision, /ECONNABORTED/);
    assert.match(decision, /status >= 500/);
});

test("a later success outranks an earlier failure within one readiness attempt", () => {
    const readiness = ui("stores/scopedReadiness.ts");
    const complete = readiness.slice(
        readiness.indexOf("complete(scope: string, ready: boolean)"),
        readiness.indexOf("isReady(scope: string)"),
    );

    // Two loads can share one attempt; the first to finish must not veto the other's success.
    assert.match(complete, /if \(state\.ready \|\| !ready\)/);
    assert.match(complete, /state\.ready = true;/);
});

test("a negative login flag is not trusted before the page has had time to hydrate", () => {
    const identityWait = ui("utils/bossIdentity.ts");
    assert.match(identityWait, /LOGOUT_SIGNAL_MIN_ATTEMPTS/);
    assert.match(identityWait, /attempt >= LOGOUT_SIGNAL_MIN_ATTEMPTS/);
});

test("the interactive import path gets a longer identity budget than reactive loads", () => {
    const identityWait = ui("utils/bossIdentity.ts");
    const aiJob = ui("components/ui/AiJob.vue");
    assert.match(identityWait, /export const INTERACTIVE_ATTEMPTS = 120/);
    assert.match(aiJob, /INTERACTIVE_ATTEMPTS/);
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
