import assert from "node:assert/strict";
import test from "node:test";
import axios, {AxiosHeaders} from "axios";
import {applicationHarness, browserImportHarness, httpFailure} from "./helpers/resumeImportHarness.ts";

for (const endpoint of ["/api/user/import/resume", "/api/user/silently/login"]) {
    test(`application adapter receives canonical proof without underscore header: ${endpoint}`, async () => {
        const app = applicationHarness();
        await app.client.post(endpoint, null, {headers: {Zp_token: "synthetic-proof"}});
        assert.equal(app.requests[0].headers.get("X-Boss-Token"), "synthetic-proof");
        assert.equal(app.requests[0].headers.has("Zp_token"), false);
        assert.equal(app.requests[0].headers.get("Authorization"), "synthetic-session");
    });
}

test("canonical proof survives and platform Axios clients retain their platform header", async () => {
    const app = applicationHarness();
    await app.client.post("/api/user/import/resume", null, {headers: {"X-Boss-Token": "synthetic-proof"}});
    assert.equal(app.requests[0].headers.get("X-Boss-Token"), "synthetic-proof");
    let platformHeaders: any;
    const platformClient = axios.create({adapter: async config => {
        platformHeaders = config.headers;
        return {status: 200, statusText: "OK", data: {}, headers: {}, config};
    }});
    await platformClient.get("https://platform.invalid/resume", {headers: {Zp_token: "platform-proof"}});
    assert.equal(platformHeaders.get("Zp_token"), "platform-proof");
    assert.equal(platformHeaders.has("X-Boss-Token"), false);
    assert.equal(AxiosHeaders.from(axios.defaults.headers.common).has("X-Boss-Token"), false);
});

test("conflicting proof is rejected before an application request is sent", async () => {
    const app = applicationHarness();
    await assert.rejects(app.client.post("/api/user/import/resume", null, {
        headers: {"X-Boss-Token": "canonical-proof", Zp_token: "different-proof"},
    }), /身份|identity/i);
    assert.equal(app.requests.length, 0);
});

test("business failures preserve response status, business code and retry headers", async () => {
    const app = applicationHarness();
    app.respondWith(async () => ({code: 500, message: "synthetic failure"}));
    await assert.rejects(app.client.post("/api/user/import/resume"), (error: any) => {
        assert.equal(error.message, "synthetic failure");
        assert.equal(error.response.status, 200);
        assert.equal(error.response.data.code, 500);
        assert.ok(error.config);
        return true;
    });
});

test("HTTP failures retain their structured identity and original message without a body message", async () => {
    const app = applicationHarness();
    let original: any;
    app.respondWith(async config => {
        original = httpFailure(config, 429, "synthetic non-JSON response", {"Retry-After": "30"});
        throw original;
    });
    await assert.rejects(app.client.post("/api/user/import/resume"), (error: any) => {
        assert.equal(error, original);
        assert.equal(error.response.status, 429);
        assert.equal(error.response.headers.get("Retry-After"), "30");
        assert.ok(error.message);
        return true;
    });
});

test("a timeout remains the original structured error instead of a bare string", async () => {
    const app = applicationHarness();
    let original: any;
    app.respondWith(async config => {
        original = httpFailure(config, 504, {message: "synthetic timeout"});
        original.code = "ECONNABORTED";
        throw original;
    });
    await assert.rejects(app.client.post("/api/user/import/resume"), error => error === original);
});

for (const change of ["account", "server", "token"] as const) {
    test(`queued import is cancelled before dispatch if its ${change} changed`, async () => {
        const app = applicationHarness();
        const config = {
            headers: {Zp_token: app.page.token},
            bossIdentityContext: {accountId: app.page.uid, token: app.page.token, serverUrl: app.server.baseUrl},
        };
        if (change === "account") app.page.uid = "other-account";
        if (change === "server") app.server.baseUrl = "https://other.invalid/";
        if (change === "token") app.page.token = "other-proof";
        await assert.rejects(app.client.post("/api/user/import/resume", null, config));
        assert.equal(app.requests.length, 0);
        assert.equal(app.notices.length, 0);
    });
}

for (const headers of [
    {Zp_token: " synthetic-proof "},
    {"zP_ToKeN": "synthetic-proof", "x-boss-token": "synthetic-proof"},
    {"X-Boss-Token": "   ", Zp_token: "synthetic-proof"},
]) {
    test(`proof normalization handles compatible header forms: ${Object.keys(headers).join(",")}`, async () => {
        const app = applicationHarness();
        await app.client.post("/api/user/import/resume", null, {headers});
        assert.equal(app.requests[0].headers.get("X-Boss-Token"), "synthetic-proof");
        assert.equal(app.requests[0].headers.has("Zp_token"), false);
    });
}

test("missing proof is not invented from another authorization header", async () => {
    const app = applicationHarness();
    await app.client.post("/api/user/userinfo", {});
    assert.equal(app.requests[0].headers.has("X-Boss-Token"), false);
    assert.equal(app.requests[0].headers.has("Zp_token"), false);
    assert.equal(app.requests[0].headers.get("Authorization"), "synthetic-session");
});

for (const http of [false, true]) {
    test(`a stale ${http ? "HTTP" : "business"} 401 cannot clear newer scoped authorization`, async () => {
        const app = applicationHarness();
        app.respondWith(async config => {
            app.memoryStorage.setItem("Authorization", "synthetic-new-session");
            if (http) throw httpFailure(config, 401, {code: 401, message: "stale"});
            return {code: 401, message: "stale"};
        });
        await assert.rejects(app.client.post("/api/user/userinfo"));
        assert.equal(app.memoryStorage.getItem("Authorization"), "synthetic-new-session");
        assert.equal(app.login.login, true);
    });
}

for (const serverUrl of ["/", "relative/backend", ""]) {
    test(`identity-context request rejects an invalid server scope before its adapter: ${JSON.stringify(serverUrl)}`, async () => {
        const app = applicationHarness();
        app.server.baseUrl = serverUrl;
        await assert.rejects(app.client.post("/api/user/import/resume", null, {
            headers: {Zp_token: app.page.token},
            bossIdentityContext: {accountId: app.page.uid, token: app.page.token, serverUrl},
        }), (error: any) => {
            assert.equal(error.code, "RESUME_SCOPE_INVALID");
            assert.match(error.message, /服务器|服务地址/);
            assert.doesNotMatch(error.message, /synthetic-proof|synthetic-session/);
            return true;
        });
        assert.equal(app.requests.length, 0, "no adapter may receive the proof for a relative destination");
    });
}

test("identity-context request rejects a null account scope before its adapter", async () => {
    const app = applicationHarness();
    app.page.uid = "";
    await assert.rejects(app.client.post("/api/user/import/resume", null, {
        headers: {"X-Boss-Token": app.page.token},
        bossIdentityContext: {accountId: "", token: app.page.token, serverUrl: app.server.baseUrl},
    }), (error: any) => {
        assert.equal(error.code, "RESUME_SCOPE_INVALID");
        return true;
    });
    assert.equal(app.requests.length, 0);
});

for (const malformed of ["https:application.invalid/", "https:/application.invalid/"]) {
    test(`real browser client never dispatches proof to a repaired relative destination: ${malformed}`, async () => {
        const app = browserImportHarness();
        app.server.baseUrl = malformed;
        await assert.rejects(app.client.post("/api/user/import/resume", null, {
            headers: {Zp_token: app.page.token},
            bossIdentityContext: {accountId: app.page.uid, token: app.page.token, serverUrl: malformed},
        }), (error: any) => error.code === "RESUME_SCOPE_INVALID");
        assert.equal(app.xhrInstances.length, 0);
        assert.equal(app.xhrCalls.length, 0);
    });
}

test("browser normalization does not bypass a captured raw-server context change", async () => {
    const app = browserImportHarness();
    const context = {accountId: app.page.uid, token: app.page.token, serverUrl: app.server.baseUrl};
    app.server.baseUrl = "https://APPLICATION.invalid:443";
    await assert.rejects(app.client.post("/api/user/import/resume", null, {
        headers: {Zp_token: app.page.token},
        bossIdentityContext: context,
    }), (error: any) => error.code === "ERR_CANCELED");
    assert.equal(app.xhrInstances.length, 0);
    assert.equal(app.xhrCalls.length, 0);
});
