import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {createRequire} from "node:module";
import {fileURLToPath} from "node:url";
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

export function applicationHarness() {
    const page = {uid: "synthetic-account", token: "synthetic-proof"};
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
        axios: axiosModule,
        "./utils/tools": {ElMessage: notify},
        "./types": {BizCodeEnum: {PRODUCT_NOT_AUTHORIZED: 5001}},
        "./stores": stores,
        "./stores/server": {ServerStore: () => server, DEFAULT_SERVER_URL: server.baseUrl},
        "./platform/utils": {Tools: {window: {_PAGE: page}}},
        "./utils/sessionContext": {
            ...session,
            scopedAuthorization: (scope: string) => session.scopedAuthorization(scope, memoryStorage),
            isScopedAuthorizationCurrent: (scope: string, proof: string) =>
                session.isScopedAuthorizationCurrent(scope, proof, memoryStorage),
            clearScopedAuthorization: (scope: string, proof: string) =>
                session.clearScopedAuthorization(scope, proof, memoryStorage),
        },
    };
    const compiled = ts.transpileModule(readFileSync(axiosUrl, "utf8"), {
        compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS},
    }).outputText;
    const module = {exports: {} as any};
    runInNewContext(compiled, {
        module,
        exports: module.exports,
        require: (name: string) => {
            if (name in dependencies) return dependencies[name];
            return require(fileURLToPath(new URL(`${name}.ts`, axiosUrl)));
        },
    });
    const client = module.exports.default as ReturnType<typeof axios.create>;
    const requests: any[] = [];
    let respond = async (_config: any): Promise<any> => ({code: 200, data: {email: "synthetic@example.invalid"}});
    client.defaults.adapter = async config => {
        requests.push(config);
        const data = await respond(config);
        return {status: 200, statusText: "OK", headers: new AxiosHeaders(), config, data};
    };
    return {
        client, page, server, login, notices, notify, requests, memoryStorage,
        respondWith: (callback: typeof respond) => { respond = callback; },
    };
}

export function importHarness() {
    const app = applicationHarness();
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
    let identity = () => waitForBossIdentity(() => app.page, app.page.uid, 2, 0);
    let refresh = async () => { userStore.user.resumeId = "new-resume"; };
    const globals: any = {
        Tools: {window: {_PAGE: app.page}},
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
        userRemoteLoad: () => refresh(),
        isUserConfigReady: () => app.login.login && !app.login.loginFailStatus,
        axios: app.client,
        axiosOriginal: {get: (...args: any[]) => { lookups.push(args); return lookup(); }},
        fetchWithGM_request: (...args: any[]) => { downloads.push(args); return download(); },
        ElMessage: app.notify,
        logger: {warn: (...args: unknown[]) => logs.push(args), error: (...args: unknown[]) => logs.push(args)},
        Blob, FormData, ArrayBuffer,
    };
    const helperPath = new URL("../../src/utils/resumeImportError.ts", import.meta.url);
    try {
        Object.assign(globals, require(fileURLToPath(helperPath)));
    } catch (error: any) {
        if (error.code !== "MODULE_NOT_FOUND") throw error;
    }
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

export function httpFailure(config: any, status: number, data: any, headers: Record<string, string> = {}) {
    return new AxiosError("Synthetic transport failure", "ERR_BAD_RESPONSE", config, undefined, {
        status, statusText: "Rejected", config, data, headers: new AxiosHeaders(headers),
    });
}
