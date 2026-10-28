import assert from "node:assert/strict";
import test from "node:test";
import {runInNewContext} from "node:vm";
import {browserImportHarness, deferred, httpFailure, importHarness} from "./helpers/resumeImportHarness.ts";
import {userSessionScope} from "../src/utils/sessionContext.ts";

for (const [status, code, message] of [
    [429, 429, "请求过于频繁，请稍后重试"],
    [403, 2003, "身份校验失败"],
] as const) {
    test(`application failure ${status}/${code} is reported once without blaming the attachment`, async () => {
        const app = importHarness();
        app.respondWith(async config => { throw httpFailure(config, status, {code, message}, {"Retry-After": "30"}); });
        await app.run();
        assert.equal(app.notices.length, 1);
        assert.match(app.notices[0].message, status === 429 ? /频繁|限流/ : /身份/);
        assert.doesNotMatch(app.notices[0].message, /确认BOSS附件简历和网络|附件简历和网络连接/);
        if (status === 429) assert.match(app.notices[0].message, /30/);
        assert.equal(app.requests.length, 1, "uploads must not retry automatically");
        assert.equal(app.userStore.user.resumeId, "old-resume");
    });
}

test("a fulfilled identity rejection is not misreported as a file parsing failure", async () => {
    const app = importHarness();
    app.respondWith(async () => ({code: 2003, message: "身份校验失败"}));
    await app.run();
    assert.equal(app.notices.length, 1);
    assert.match(app.notices[0].message, /身份/);
    assert.doesNotMatch(app.notices[0].message, /解析|附件简历和网络/);
});

test("overlapping clicks share no additional lookup, download or upload", async () => {
    const app = importHarness();
    const wait = deferred<any>();
    app.lookupWith(() => wait.promise);
    const first = app.run();
    const second = app.run();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(app.lookups.length, 1);
    assert.equal(app.loading.value, true);
    wait.resolve({data: {zpData: {attachmentList: []}}});
    await Promise.all([first, second]);
    assert.equal(app.loading.value, false);
    assert.equal(app.requests.length, 0);
});

test("does not read or upload a resume before the application session is ready", async () => {
    const app = importHarness();
    app.login.login = false;
    app.login.loginFailStatus = true;

    await app.run();

    assert.equal(app.lookups.length, 0);
    assert.equal(app.downloads.length, 0);
    assert.equal(app.requests.length, 0);
    assert.equal(app.notices.length, 1);
    assert.match(app.notices[0].message, /账号|连接/);
});

test("first-use import establishes the application session before reading and saving the resume", async () => {
    const app = importHarness();
    app.login.login = false;
    app.login.loginFailStatus = false;
    app.login.accountScope = null;
    app.login.bossToken = null;

    await app.run();

    assert.equal(app.lookups.length, 1);
    assert.equal(app.downloads.length, 1);
    assert.equal(app.requests.length, 1);
    assert.equal(app.requests[0].headers.get("Authorization"), "synthetic-session");
    assert.equal(app.requests[0].headers.has("X-Boss-Token"), false);
    assert.equal(app.login.bossToken, app.page.cookieToken);
    assert.equal(app.status.value, "成功导入简历");
});

test("a BOSS identity change while the application session is loading stops before resume lookup", async () => {
    const app = importHarness();
    const wait = deferred<void>();
    app.login.login = false;
    app.refreshWith(async () => {
        await wait.promise;
        app.page.uid = "other-account";
        app.page.cookieToken = "other-account-proof";
        app.login.login = true;
        app.login.accountScope = userSessionScope(app.page.uid, app.server.baseUrl);
        app.memoryStorage.setItem("AuthorizationScope", app.login.accountScope!);
        app.memoryStorage.setItem("Authorization", "other-account-session");
    });

    const pending = app.run();
    await new Promise(resolve => setImmediate(resolve));
    wait.resolve();
    await pending;

    assert.equal(app.page.uid, "other-account");
    assert.equal(app.login.login, true);
    assert.equal(app.login.loginFailStatus, false);
    assert.equal(app.login.accountScope, userSessionScope(app.page.uid, app.server.baseUrl));
    assert.equal(app.lookups.length, 0);
    assert.equal(app.downloads.length, 0);
    assert.equal(app.requests.length, 0);
    assert.equal(app.notices.length, 0);
});

test("a BOSS cookie rotation alone does not block a resume import for the same account", async () => {
    // BOSS rotates its own cookie on its own schedule. That is not an account switch and must not
    // be treated as one: the import is authorized by the application session, not by the cookie.
    const app = importHarness();
    app.page.cookieToken = "rotated-proof-for-same-account";

    await app.run();

    assert.equal(app.lookups.length, 1);
    assert.equal(app.downloads.length, 1);
    assert.equal(app.requests.length, 1);
    assert.equal(app.requests[0].headers.get("Authorization"), "synthetic-session");
    assert.equal(app.status.value, "成功导入简历");
    // Any notice raised must be about reconnecting, never about the account being wrong.
    assert.ok(app.notices.every(notice => !/账号|身份|切换/.test(String(notice.message))));
});

test("a different BOSS account cannot import a resume without its own application session", async () => {
    const app = importHarness();
    app.page.uid = "different-account";
    app.page.cookieToken = "different-account-proof";
    app.refreshWith(async () => {
        throw new Error("synthetic identity mismatch");
    });

    await app.run();

    assert.equal(app.lookups.length, 0);
    assert.equal(app.downloads.length, 0);
    assert.equal(app.requests.length, 0);
    assert.equal(app.notices.length, 1);
    assert.match(app.notices[0].message, /身份|登录|BOSS/);
});

test("missing bst cookie does not reuse a stale page token for resume import", async () => {
    const app = importHarness();
    app.page.cookieToken = "";
    app.page.token = "previously-verified-page-token";

    await app.run();

    assert.equal(app.lookups.length, 0);
    assert.equal(app.downloads.length, 0);
    assert.equal(app.requests.length, 0);
    assert.equal(app.notices.length, 1);
    assert.match(app.notices[0].message, /身份|登录|BOSS/);
});

test("import waits for a ready live identity before lookup or changing the saved-status badge", async () => {
    const app = importHarness();
    const wait = deferred<{accountId: string; token: string}>();
    app.page.token = "";
    app.page.cookieToken = "";
    app.identityWith(() => wait.promise);
    const pending = app.run();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(app.lookups.length, 0);
    assert.equal(app.status.value, "成功导入简历");
    app.page.token = "ready-proof";
    app.page.cookieToken = "ready-proof";
    wait.resolve({accountId: app.page.uid, token: app.page.token});
    await pending;
    assert.equal(app.lookups.length, 1);
    assert.equal(app.lookups[0][1].headers.Zp_token, "ready-proof");
    assert.equal(app.downloads[0][1].headers.Zp_token, "ready-proof");
    assert.equal(app.requests[0].headers.get("Authorization"), "synthetic-session");
    assert.equal(app.requests[0].headers.has("X-Boss-Token"), false);
});

for (const change of ["account", "server", "token"] as const) {
    test(`a ${change} change during download prevents upload and stale notices`, async () => {
        const app = importHarness();
        const wait = deferred<any>();
        app.downloadWith(() => wait.promise);
        const pending = app.run();
        await new Promise(resolve => setImmediate(resolve));
        if (change === "account") app.page.uid = "other-account";
        if (change === "server") app.server.baseUrl = "https://other.invalid/";
        if (change === "token") {
            app.page.token = "other-proof";
            app.page.cookieToken = "other-proof";
        }
        wait.resolve({status: 200, response: new Uint8Array([1, 2]).buffer});
        await pending;
        assert.equal(app.requests.length, 0);
        assert.equal(app.notices.length, 0);
        assert.equal(app.loading.value, false);
    });
}

test("a stale rejected download cannot overwrite the new account badge", async () => {
    const app = importHarness();
    const wait = deferred<any>();
    app.downloadWith(() => wait.promise);
    const pending = app.run();
    await new Promise(resolve => setImmediate(resolve));
    app.page.uid = "other-account";
    app.status.value = "成功导入简历";
    wait.reject(new Error("synthetic old-account error"));
    await pending;
    assert.equal(app.status.value, "成功导入简历");
    assert.equal(app.notices.length, 0);
});

for (const response of [
    {status: 403, response: new Uint8Array([1, 2]).buffer},
    {status: 200, response: undefined},
    {status: 200, response: new ArrayBuffer(0)},
]) {
    test(`invalid download is rejected before uploading: ${response.status}/${response.response?.byteLength}`, async () => {
        const app = importHarness();
        app.downloadWith(async () => response);
        await app.run();
        assert.equal(app.requests.length, 0);
        assert.equal(app.notices.length, 1);
        assert.match(app.notices[0].message, /下载|附件/);
    });
}

test("saved import does not perform a second manual login outside the profile loader", async () => {
    const app = importHarness();
    await app.run();
    assert.equal(app.userStore.user.resumeId, "new-resume");
    assert.equal(app.status.value, "成功导入简历");
    assert.equal(app.requests.filter(config => config.url.includes("/silently/login")).length, 0);
});

test("only the handler's resume upload receives a bounded two-minute timeout and is never retried", async () => {
    const app = importHarness();
    await app.run();
    assert.equal(app.requests.length, 1);
    assert.equal(app.requests[0].url, "/api/user/import/resume");
    assert.equal(app.requests[0].timeout, 120000);
    await app.client.post("/api/user/userinfo", {});
    assert.equal(app.requests[1].timeout, 10000);
    assert.equal(app.client.defaults.timeout, 10000);
});

test("session refresh failure preserves saved success and explicitly warns about refresh", async () => {
    const app = importHarness();
    app.refreshWith(async () => { throw new Error("synthetic refresh failed"); });
    await app.run();
    assert.equal(app.userStore.user.resumeId, "new-resume");
    assert.equal(app.status.value, "成功导入简历");
    assert.ok(app.notices.some(notice => /保存成功/.test(notice.message) && /刷新|重新连接/.test(notice.message)));
    assert.ok(app.notices.every(notice => !/导入简历失败/.test(notice.message)));
});

test("import logs exclude proof, response bodies and untrusted exception messages", async () => {
    const app = importHarness();
    app.respondWith(async config => {
        throw httpFailure(config, 403, {code: 2003, message: "synthetic-secret-in-message", token: "synthetic-secret-in-body"});
    });
    await app.run();
    assert.doesNotMatch(JSON.stringify(app.logs), /synthetic-secret|synthetic-proof|synthetic-session/);
});

test("a stale upload rejection does not display an application toast for a new identity", async () => {
    const app = importHarness();
    const wait = deferred<any>();
    app.respondWith(config => wait.promise.then(() => {
        throw httpFailure(config, 429, {message: "synthetic stale limiter"});
    }));
    const pending = app.run();
    await new Promise(resolve => setImmediate(resolve));
    app.page.cookieToken = "other-proof";
    wait.resolve(undefined);
    await pending;
    assert.equal(app.notices.length, 0);
});

test("valid binary downloads from another userscript realm are accepted without changing bytes", async () => {
    const app = importHarness();
    const bytes = runInNewContext("new Uint8Array([37, 80, 68, 70, 45]).buffer");
    app.downloadWith(async () => ({status: 200, response: bytes}));
    await app.run();
    assert.equal(app.requests.filter(config => config.url === "/api/user/import/resume").length, 1);
    const file = app.requests[0].data.get("file");
    assert.deepEqual(Array.from(new Uint8Array(await file.arrayBuffer())), [37, 80, 68, 70, 45]);
    assert.equal(app.status.value, "成功导入简历");
});

test("untrusted nonnumeric business codes cannot put response objects in import logs", async () => {
    const app = importHarness();
    app.respondWith(async config => {
        throw httpFailure(config, 403, {code: {token: "synthetic-secret-in-code"}, message: "rejected"});
    });
    await app.run();
    assert.doesNotMatch(JSON.stringify(app.logs), /synthetic-secret|token/);
});

for (const serverUrl of ["/", "relative/backend", ""]) {
    test(`invalid server scope stops import before any attachment action: ${JSON.stringify(serverUrl)}`, async () => {
        const app = importHarness();
        app.server.baseUrl = serverUrl;
        await app.run();
        assert.equal(app.lookups.length, 0, "invalid destinations must not read BOSS attachments");
        assert.equal(app.downloads.length, 0, "invalid destinations must not download a resume");
        assert.equal(app.requests.length, 0, "invalid destinations must never receive identity proof or a file");
        assert.equal(app.userStore.user.resumeId, "old-resume");
        assert.equal(app.loading.value, false);
        assert.equal(app.notices.length, 1);
        assert.match(app.notices[0].message, /服务器|服务地址/);
        assert.match(app.notices[0].message, /无效|完整|HTTP/);
        assert.doesNotMatch(JSON.stringify(app.logs), /synthetic-proof|synthetic-session/);
    });
}

test("scope validation waits for an initially missing live account before allowing a valid import", async () => {
    const app = importHarness();
    const wait = deferred<{accountId: string; token: string}>();
    app.page.uid = "";
    app.page.token = "";
    app.page.cookieToken = "";
    app.identityWith(() => wait.promise);
    const pending = app.run();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(app.lookups.length, 0);
    assert.equal(app.notices.length, 0);
    assert.equal(app.status.value, "成功导入简历");
    app.page.uid = "ready-account";
    app.page.token = "ready-proof";
    app.page.cookieToken = "ready-proof";
    wait.resolve({accountId: app.page.uid, token: app.page.token});
    await pending;
    assert.equal(app.lookups.length, 1);
    assert.equal(app.downloads.length, 1);
    assert.equal(app.requests.length, 1);
    assert.equal(app.requests[0].headers.has("X-Boss-Token"), false);
    assert.equal(app.status.value, "成功导入简历");
});

test("BOSS requests use the current bst cookie instead of a stale page token", async () => {
    const app = importHarness();
    app.page.token = "stale-page-proof";
    app.page.cookieToken = "rotated-live-cookie";

    await app.run();

    assert.equal(app.lookups[0][1].headers.Zp_token, "rotated-live-cookie");
    assert.equal(app.downloads[0][1].headers.Zp_token, "rotated-live-cookie");
    assert.equal(app.requests[0].headers.get("Authorization"), "synthetic-session");
    assert.equal(app.requests[0].headers.has("X-Boss-Token"), false);
    assert.equal(app.requests[0].headers.has("Zp_token"), false);
    assert.equal(app.requests[0].data.has("uniqueId"), false);
});

for (const malformed of ["https:application.invalid/", "https:/application.invalid/"]) {
    test(`real browser import rejects malformed destination before any attachment or XHR: ${malformed}`, async () => {
        const app = browserImportHarness();
        app.server.baseUrl = malformed;
        await app.run();
        assert.equal(app.lookups.length, 0);
        assert.equal(app.downloads.length, 0);
        assert.equal(app.xhrInstances.length, 0);
        assert.equal(app.xhrCalls.length, 0);
        assert.equal(app.notices.length, 1);
        assert.match(app.notices[0].message, /服务器|服务地址/);
    });
}

for (const [serverUrl, destination] of [
    ["https://application.invalid/", "https://application.invalid/api/user/import/resume"],
    [" https://APPLICATION.invalid:443/custom/backend/ ", "https://application.invalid/custom/backend/api/user/import/resume"],
    ["https://application.invalid/custom/backend", "https://application.invalid/custom/backend/api/user/import/resume"],
    ["https://application.invalid/custom/backend///", "https://application.invalid/custom/backend/api/user/import/resume"],
]) {
    test(`production handler dispatches to normalized application URL through real XHR: ${JSON.stringify(serverUrl)}`, async () => {
        const app = browserImportHarness();
        app.server.baseUrl = serverUrl;
        app.memoryStorage.setItem("AuthorizationScope", userSessionScope(app.page.uid, serverUrl)!);
        await app.run();
        assert.equal(app.xhrCalls.length, 1);
        const sent = app.xhrCalls[0];
        assert.equal(sent.rawUrl, destination);
        assert.equal(sent.url, destination);
        assert.equal(sent.headers.get("authorization"), "synthetic-session");
        assert.equal(sent.headers.has("x-boss-token"), false);
        assert.equal(sent.headers.has("zp_token"), false);
        assert.equal(sent.data.get("file").size > 0, true);
        assert.equal(app.status.value, "成功导入简历");
    });
}
