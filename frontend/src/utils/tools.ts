import {Tools} from "../platform/utils";
import {LoginStore, UserStore} from "../stores";
import axios from '../axios'
import {ElMessage as originalElMessage} from "element-plus";
import {LogRecorder} from "../logging/record";
import {GM_xmlhttpRequest} from "$";
import {classifySilentLoginResponse, SilentLoginOutcome} from "./silentLoginState";
import {
    clearScopedAuthorization,
    scopedAuthorization,
    storeScopedAuthorization,
    userSessionScope
} from "./sessionContext";
import {ServerStore} from "../stores/server";
import {BossIdentity, waitForBossIdentity} from "./bossIdentity";

const logRecorder = new LogRecorder();
const silentLoginRequests = new Map<string, Map<string, Promise<SilentLoginOutcome>>>();

function currentBossToken(): string {
    return String(Tools.getCookieValue("bst") ?? "").trim();
}

export async function silentlyLogin(
    bossUserId = "",
    bossToken?: string
): Promise<SilentLoginOutcome> {
    const identity: BossIdentity = bossToken
        ? {
            accountId: String(bossUserId || "").trim(),
            token: bossToken
        }
        : await waitForBossIdentity(() => Tools.window?._PAGE, bossUserId, 240, 250, currentBossToken);
    const requestedAccountId = identity.accountId;
    const requestedServerUrl = ServerStore().baseUrl;
    const scope = userSessionScope(requestedAccountId, requestedServerUrl);
    if (!scope) {
        throw new Error("登录上下文无效");
    }

    let scopedRequests = silentLoginRequests.get(scope);
    const existingRequest = scopedRequests?.get(identity.token);
    if (existingRequest) {
        return existingRequest;
    }

    if (!scopedRequests) {
        scopedRequests = new Map();
        silentLoginRequests.set(scope, scopedRequests);
    }
    let trackedRequest!: Promise<SilentLoginOutcome>;
    trackedRequest = performSilentLogin(identity, requestedServerUrl, scope)
        .finally(() => {
            const requests = silentLoginRequests.get(scope);
            if (requests?.get(identity.token) === trackedRequest) {
                requests.delete(identity.token);
                if (requests.size === 0) {
                    silentLoginRequests.delete(scope);
                }
            }
        });
    scopedRequests.set(identity.token, trackedRequest);
    return trackedRequest;
}

async function performSilentLogin(
    identity: BossIdentity,
    requestedServerUrl: string,
    sessionScope: string
): Promise<SilentLoginOutcome> {
    const {accountId: requestedAccountId, token} = identity;
    const loginStore = LoginStore();
    const contextIsCurrent = () => {
        const page = Tools.window?._PAGE;
        const pageAccountId = String(page?.uid ?? "").trim();
        const pageToken = currentBossToken();
        return ServerStore().baseUrl === requestedServerUrl
            && pageAccountId === requestedAccountId
            && pageToken === token;
    };

    try {
        if (!contextIsCurrent()) {
            throw new Error("BOSS账号或服务器已切换，请重试");
        }
        if (loginStore.login
            && loginStore.accountScope === sessionScope
            && scopedAuthorization(sessionScope)
            && loginStore.bossToken === token) {
            return "authenticated";
        }
        if (loginStore.accountScope !== sessionScope) {
            loginStore.reset();
            UserStore().switchAccount(requestedAccountId, requestedServerUrl);
        }

        const resp = await axios.post(
            "/api/user/silently/login?uniqueId=" + encodeURIComponent(requestedAccountId),
            null,
            {headers: {"Zp_token": token}}
        );
        if (!contextIsCurrent()) {
            throw new Error("BOSS账号或服务器已切换，请重试");
        }
        const outcome = classifySilentLoginResponse(resp);
        if (outcome === "legacy-registration-required") {
            const previousAuthorization = scopedAuthorization(sessionScope);
            clearScopedAuthorization(sessionScope, token);
            if (!previousAuthorization || previousAuthorization === token) {
                loginStore.loginFailForScope(sessionScope);
                if (!loginStore.accountScope) {
                    loginStore.loginFail();
                }
            }
            logRecorder.warn("服务端尚未支持首次登录自动建档");
            originalElMessage.info({
                message: "当前服务端版本尚未支持首次登录自动创建账号，这不是简历未导入导致的。请更新服务端后重试，暂勿为解决登录问题上传简历。",
                duration: 9000,
                showClose: true
            });
            return outcome;
        }

        storeScopedAuthorization(resp.data.data, sessionScope);
        loginStore.loginSuccess(requestedAccountId, sessionScope, token)
        logRecorder.info("静默登录成功")
        return outcome;
    } catch (e: any) {
        const details = [
            typeof e?.code === "string" ? e.code : "",
            Number.isInteger(e?.response?.status) ? `HTTP ${e.response.status}` : "",
            typeof e?.message === "string" ? e.message : "",
        ].filter(Boolean).join(" ") || (typeof e?.name === "string" ? e.name : "unknown");
        logRecorder.error("静默登录失败", details)
        const authorizationWasCurrent = scopedAuthorization(sessionScope) === token;
        clearScopedAuthorization(sessionScope, token);
        if (contextIsCurrent() && authorizationWasCurrent) {
            loginStore.loginFailForScope(sessionScope);
        }
        throw e;
    }
}

export const isProdEnv = (): boolean => {
    return import.meta.env.MODE === 'production';
}


const wrapMessage = (options: any) => {
    if (typeof options === 'string') {
        return "[AI助理] " + options
    }
    if (options && options.message) {
        options.message = "[AI助理] " + options.message
    }
    return options
}

export const ElMessage = ((options: any) => {
    return originalElMessage(wrapMessage(options))
}) as typeof originalElMessage

Object.assign(ElMessage, originalElMessage)

const methods = ['success', 'warning', 'info', 'error'] as const
methods.forEach(type => {
    (ElMessage as any)[type] = (options: any) => {
        return (originalElMessage as any)[type](wrapMessage(options))
    }
})

// ajax请求；不会跨域
export async function fetchWithGM_request(url: string, options: any) {
    return new Promise((resolve, reject) => {
        GM_xmlhttpRequest({
            method: options.method || 'GET',
            url: url,
            headers: options.headers,
            responseType: options.responseType || 'json', // 默认为json，根据需要修改
            data: options.data,
            onload: response => {
                if (response.status === 200) {
                    resolve(response);
                } else {
                    reject(new Error(`Request failed with status: ${response.status}`));
                }
            },
            onerror: () => {
                reject(new Error('Network error'));
            },
            ontimeout: () => {
                reject(new Error('Request timed out'));
            }
        });
    });
}


export const loginInterceptor = (): boolean => {
    const token = currentBossToken();
    if (!token) {
        ElMessage({
            message: "请先登录Boss",
            type: 'error',
            duration: 3000
        })
        return false;
    }

    return true;
}
