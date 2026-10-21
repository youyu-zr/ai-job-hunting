import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {createRequire} from "node:module";
import {pathToFileURL} from "node:url";
import {runInNewContext} from "node:vm";
import axios, {AxiosError, AxiosHeaders} from "axios";
import * as axiosModule from "axios";
import ts from "typescript";
import * as session from "../../src/utils/sessionContext.ts";
import {waitForBossIdentity} from "../../src/utils/bossIdentity.ts";

const require = createRequire(import.meta.url);
const axiosUrl = new URL("../../src/axios.ts", import.meta.url);

export function deferred<T>() {
    let resolve!: (value: T) => void;
    let reject!: (reason: unknown) => void;
    const promise = new Promise<T>((ok, fail) => {
        resolve = ok;
        reject = fail;
    });
    return {promise, resolve, reject};
}

export function applicationHarness(implementation: typeof axiosModule = axiosModule) {
    const page = {uid: "synthetic-account", token: "synthetic-proof", cookieToken: "synthetic-proof"};
    const storage = new Map<string, string>();
    const memoryStorage = {
        getItem: (key: string) => storage.get(key) ?? null,
        setItem: (key: string, value: string) => storage.set(key, value),
        removeItem: (key: string) => storage.delete(key),
    };
    const server = {
        baseUrl: "https://application.invalid/",
        setStatus() {},
    };
    const login = {
        login: true,
        accountScope: session.userSessionScope(page.uid, server.baseUrl),
        bossToken: page.cookieToken,
        loginFailStatus: false,
        loginSuccess(accountId: string, scope: string) {
            this.login = true;
            this.accountScope = scope;
        },
        loginFailForScope(scope: string) {
            if (this.accountScope === scope) this.login = false;
        },
    };
    session.storeScopedAuthorization("synthetic-session", login.accountScope!, memoryStorage);
    const notices: any[] = [];
    const notify = (notice: any) => notices.push(notice);
    const stores = {LoginStore: () => login, ProductStore: () => ({setShowProduct() {}})};
    const dependencies: Record<string, unknown> = {
        axios: implementation,
        "./utils/tools": {ElMessage: notify},
        "./types": {BizCodeEnum: {PRODUCT_NOT_AUTHORIZED: 5001}},
        "./stores": stores,
        "./stores/server": {ServerStore: () => server, DEFAULT_SERVER_URL: server.baseUrl},
        "./platform/utils": {Tools: {
            window: {_PAGE: page},
            getCookieValue: (key: string) => key === "bst" ? page.cookieToken : null,
        }},
        "./utils/sessionContext": {
            ...session,
            scopedAuthorization: (scope: string) => session.scopedAuthorization(scope, memoryStorage),
            isScopedAuthorizationCurrent: (scope: string, proof: string) =>
                session.isScopedAuthorizationCurrent(scope, proof, memoryStorage),
            clearScopedAuthorization: (scope: string, proof: string) =>
                session.clearScopedAuthorization(scope, proof, memoryStorage),
        },
    };
    const modules = new Map<string, any>();
    const loadModule = (url: URL): any => {
        if (modules.has(url.href)) return modules.get(url.href);
        const module = {exports: {} as any};
        modules.set(url.href, module.exports);
        const compiled = ts.transpileModule(readFileSync(url, "utf8"), {
            compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS},
        }).outputText;
        runInNewContext(compiled, {
            module,
            exports: module.exports,
            require: (name: string) => {
                if (name in dependencies) return dependencies[name];
                return loadModule(new URL(`${name}.ts`, url));
            },
        });
        return module.exports;
    };
    const client = loadModule(axiosUrl).default as ReturnType<typeof axios.create>;
    const requests: any[] = [];
    let respond = async (_config: any): Promise<any> => ({code: 200, data: {email: "synthetic@example.invalid"}});
    client.defaults.adapter = async config => {
        requests.push(config);
        const data = await respond(config);
        return {status: 200, statusText: "OK", headers: new implementation.AxiosHeaders(), config, data};
    };
    return {
        client, page, server, login, notices, notify, requests, memoryStorage,
        errorHelpers: loadModule(new URL("../../src/utils/resumeImportError.ts", import.meta.url)),
        respondWith: (callback: typeof respond) => { respond = callback; },
    };
}

export function importHarness(app = applicationHarness()) {
    const userStore = {user: {resumeId: "old-resume", aiSeatStatus: false}};
    const logs: unknown[][] = [];
    const lookups: any[] = [];
    const downloads: any[] = [];
    const loading = {value: false};
    const status = {value: "成功导入简历"};
    let lookup = async (): Promise<any> => ({data: {zpData: {attachmentList: [{resumeId: "new-resume"}]}}});
    let download = async (): Promise<any> => ({
        status: 200,
        response: new TextEncoder().encode("%PDF-synthetic").buffer,
    });
    let identity = () => waitForBossIdentity(
        () => app.page,
        app.page.uid,
        2,
        0,
        () => app.page.cookieToken,
    );
    let refresh = async () => { userStore.user.resumeId = "new-resume"; };
    const globals: any = {
        Tools: {
            window: {_PAGE: app.page},
            getCookieValue: (key: string) => key === "bst"
                ? app.page.cookieToken
                : null,
        },
        serverStore: app.server,
        userStore,
        loginStore: app.login,
        importResumeLoading: loading,
        resumeImportStatus: status,
        resumeImportAttempted: false,
        loginInterceptor: () => Boolean(app.page.token),
        waitForBossIdentity: (...args: any[]) => identity(),
        userSessionScope: session.userSessionScope,
        storeScopedAuthorization: (proof: string, scope: string) =>
            session.storeScopedAuthorization(proof, scope, app.memoryStorage),
        userRemoteLoad: async () => {
            const scope = session.userSessionScope(app.page.uid, app.server.baseUrl);
            if (scope && !app.login.loginFailStatus && app.login.accountScope !== scope) {
                app.login.login = true;
                app.login.accountScope = scope;
                session.storeScopedAuthorization("synthetic-session", scope, app.memoryStorage);
            }
            await refresh();
            app.login.bossToken = app.page.cookieToken;
        },
        isApplicationSessionReady: (expectedBossToken?: string) => app.login.login && !app.login.loginFailStatus
            && app.login.bossToken === expectedBossToken
            && Boolean(session.scopedAuthorization(
                session.userSessionScope(app.page.uid, app.server.baseUrl),
                app.memoryStorage,
            )),
        axios: app.client,
        axiosOriginal: {get: (...args: any[]) => { lookups.push(args); return lookup(); }},
        fetchWithGM_request: (...args: any[]) => { downloads.push(args); return download(); },
        ElMessage: app.notify,
        logger: {warn: (...args: unknown[]) => logs.push(args), error: (...args: unknown[]) => logs.push(args)},
        Blob, FormData, ArrayBuffer,
    };
    Object.assign(globals, app.errorHelpers);
    const vue = readFileSync(new URL("../../src/components/ui/AiJob.vue", import.meta.url), "utf8");
    const script = vue.slice(vue.indexOf('<script setup lang="ts">') + '<script setup lang="ts">'.length, vue.indexOf("</script>"));
    const source = ts.createSourceFile("AiJob.ts", script, ts.ScriptTarget.Latest, true);
    const declaration = source.statements.find(node => ts.isVariableStatement(node)
        && node.declarationList.declarations.some(item => item.name.getText(source) === "handlerImport"));
    assert.ok(declaration, "production import handler must exist");
    const code = ts.createPrinter().printNode(ts.EmitHint.Unspecified, declaration, source);
    runInNewContext(ts.transpileModule(`${code}\nglobalThis.runImport = handlerImport;`, {
        compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS},
    }).outputText, globals);
    return {
        ...app, userStore, logs, lookups, downloads, loading, status,
        run: globals.runImport as () => Promise<void>,
        lookupWith: (callback: typeof lookup) => { lookup = callback; },
        downloadWith: (callback: typeof download) => { download = callback; },
        identityWith: (callback: typeof identity) => { identity = callback; },
        refreshWith: (callback: typeof refresh) => { refresh = callback; },
    };
}

export function browserImportHarness() {
    const location = new URL("https://www.zhipin.com/web/geek/jobs");
    const xhrInstances: any[] = [];
    const xhrCalls: Array<{method: string; rawUrl: string; url: string; headers: Map<string, string>; data: any}> = [];
    class MemoryXMLHttpRequest {
        method = "";
        rawUrl = "";
        responseURL = "";
        headers = new Map<string, string>();
        status = 200;
        statusText = "OK";
        responseText = JSON.stringify({code: 200, data: {email: "synthetic@example.invalid"}});
        onloadend: (() => void) | null = null;
        upload = {addEventListener() {}};
        constructor() { xhrInstances.push(this); }
        open(method: string, url: string) {
            this.method = method;
            this.rawUrl = url;
            this.responseURL = new URL(url, location).href;
        }
        setRequestHeader(name: string, value: string) { this.headers.set(name.toLowerCase(), value); }
        getAllResponseHeaders() { return "Content-Type: application/json\r\n"; }
        addEventListener() {}
        abort() {}
        send(data: any) {
            xhrCalls.push({method: this.method, rawUrl: this.rawUrl, url: this.responseURL, headers: this.headers, data});
            queueMicrotask(() => this.onloadend?.());
        }
    }
    const document = {
        cookie: "",
        createElement: () => ({
            setAttribute(_name: string, value: string) {
                const parsed = new URL(value, location);
                for (const key of ["href", "protocol", "host", "search", "hash", "hostname", "port", "pathname"] as const) {
                    (this as any)[key] = parsed[key];
                }
            },
        }),
    };
    const module = {exports: {} as any};
    const bundleUrl = new URL("./dist/browser/axios.cjs", pathToFileURL(require.resolve("axios/package.json")));
    runInNewContext(readFileSync(bundleUrl, "utf8"), {
        module, exports: module.exports,
        XMLHttpRequest: MemoryXMLHttpRequest,
        window: {location}, document, navigator: {userAgent: "synthetic-browser"},
        FormData, Blob, URL, URLSearchParams, ArrayBuffer,
        setTimeout, clearTimeout, queueMicrotask, btoa,
    });
    const browserAxios = module.exports;
    const app = applicationHarness({...browserAxios, default: browserAxios});
    app.client.defaults.adapter = "xhr";
    return {...importHarness(app), xhrInstances, xhrCalls};
}

export function httpFailure(config: any, status: number, data: any, headers: Record<string, string> = {}) {
    return new AxiosError("Synthetic transport failure", "ERR_BAD_RESPONSE", config, undefined, {
        status, statusText: "Rejected", config, data, headers: new AxiosHeaders(headers),
    });
}
