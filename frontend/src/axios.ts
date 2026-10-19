import axios, {AxiosError} from "axios";
import {ElMessage} from "./utils/tools";
import {BizCodeEnum} from "./types";
import {ProductStore} from "./stores";
import {DEFAULT_SERVER_URL, ServerStore} from "./stores/server";
import {LoginStore} from "./stores";
import {Tools} from "./platform/utils";
import {
    clearScopedAuthorization,
    isScopedAuthorizationCurrent,
    scopedAuthorization,
    userSessionScope
} from "./utils/sessionContext";
import {installBossIdentityTransport} from "./utils/bossIdentityTransport";
import {applicationResponseError, markRequestErrorNotified, rateLimitMessage} from "./utils/resumeImportError";


/**
 * 创建自定义的axios对象，实现请求拦截器和响应拦截器
 *
 */
const request = axios.create({
    // 超时时间
    timeout: 10000,
    // 格式为json数据；字符编码utf-8
    headers: {
        'Content-Type': 'application/json; charset=utf-8',
    }
})
installBossIdentityTransport(request);

/**
 * 请求拦截器
 */
request.interceptors.request.use(req => {
        let serverUrl = DEFAULT_SERVER_URL
        try {
            const serverStore = ServerStore()
            serverUrl = serverStore.baseUrl
            req.baseURL = serverUrl
        } catch (e) {
            // 如果在 Pinia 初始化前调用了 axios，回退到默认地址
            req.baseURL = DEFAULT_SERVER_URL
        }

        const accountId = String(Tools.window?._PAGE?.uid ?? "").trim()
        const expectedIdentity = req.bossIdentityContext
        if (expectedIdentity && (serverUrl !== expectedIdentity.serverUrl
            || accountId !== expectedIdentity.accountId
            || String(Tools.window?._PAGE?.token ?? "").trim() !== expectedIdentity.token)) {
            throw new AxiosError("BOSS账号、登录身份或服务器已变更，请重新导入", AxiosError.ERR_CANCELED, req)
        }
        const scope = userSessionScope(accountId, serverUrl)
        ;(req as any).__aiSessionScope = scope
        ;(req as any).__aiServerUrl = serverUrl
        const authorization = scopedAuthorization(scope)
        ;(req as any).__aiAuthorization = authorization

        // Never let an explicitly supplied or stale token cross the current account/server boundary.
        if (authorization) {
            req.headers.set('Authorization', authorization);
        } else {
            req.headers.delete('Authorization');
        }
        return req;
    }
)

function isCurrentServerRequest(config: any): boolean {
    return Boolean(config?.__aiServerUrl)
        && config.__aiServerUrl === ServerStore().baseUrl
}

function isCurrentSessionRequest(config: any): boolean {
    const currentScope = userSessionScope(
        String(Tools.window?._PAGE?.uid ?? "").trim(),
        ServerStore().baseUrl
    )
    return Boolean(config?.__aiSessionScope && config.__aiSessionScope === currentScope)
}

function isCurrentImportRequest(config: any): boolean {
    const expected = config?.bossIdentityContext;
    return !expected || (isCurrentSessionRequest(config)
        && isCurrentServerRequest(config)
        && String(Tools.window?._PAGE?.token ?? "").trim() === expected.token);
}

function notifyRequestError(error: any, options: any): void {
    if (!isCurrentImportRequest(error.config)) {
        return;
    }
    ElMessage(options);
    markRequestErrorNotified(error);
}

function handlerErrorCode(result: any) {
    if (!result || result?.code < 5000) {
        return;
    }
    if (result.code === BizCodeEnum.PRODUCT_NOT_AUTHORIZED) {
        const productStore = ProductStore()
        productStore.setShowProduct(true)
    }
}

/**
 * 响应拦截器
 */
request.interceptors.response.use((resp: any) => {
        const serverStore = ServerStore()
        if (isCurrentServerRequest(resp.config)) {
            serverStore.setStatus('online')
        }

        // http的响应状态码会进入这里
        let result = resp.data || {};
        if (result.code === 200) {
            return resp;
        }
        //  调用方处理
        if (result.code >= 2000 && result.code < 5000) {
            return resp;
        }

        const error = applicationResponseError(resp);
        if (result.code === 401) {
            const scope = (resp.config as any)?.__aiSessionScope as string | null | undefined
            const authorizationIsCurrent = isScopedAuthorizationCurrent(
                scope || null,
                (resp.config as any)?.__aiAuthorization
            )
            clearScopedAuthorization(
                scope || null,
                (resp.config as any)?.__aiAuthorization
            )
            if (isCurrentSessionRequest(resp.config) && authorizationIsCurrent) {
                LoginStore().loginFailForScope(scope || null)
                notifyRequestError(error, {
                    type: "error",
                    message: "登录过期，请刷新页面重试"
                });
            }
            return Promise.reject(error)
        }

        if (!result.code || result.code === 500 || result.code >= 5000) {
            // 代码执行下来，说明code不为200，或者result有问题【使用弹窗提示,可能为空白】
            notifyRequestError(error, {
                type: "error",
                message: result.message ? result.message : '系统异常'
            });
            handlerErrorCode(result);
        }
        // 使请求不进入正常的响应处理函数
        return Promise.reject(error)
    },

    /**
     * 当
     * http的状态码不为200时
     * @param error
     */
    error => {
        const serverStore = ServerStore()

        if (error.response?.status === 401) {
            const scope = error.config?.__aiSessionScope as string | null | undefined
            const authorizationIsCurrent = isScopedAuthorizationCurrent(
                scope || null,
                error.config?.__aiAuthorization
            )
            clearScopedAuthorization(scope || null, error.config?.__aiAuthorization)
            if (isCurrentSessionRequest(error.config) && authorizationIsCurrent) {
                LoginStore().loginFailForScope(scope || null)
            }
        }

        // 到达前端axios设置的超时时间
        if (error.code === 'ECONNABORTED') {
            if (isCurrentServerRequest(error.config)) {
                serverStore.setStatus('offline', '请求超时')
            }
            // 弹窗提示
            notifyRequestError(error, {
                message: error.config?.url === '/api/user/import/resume'
                    ? '简历导入请求超时，保存结果尚未确认；请稍后刷新状态，勿重复上传。'
                    : '网络超时',
                type: 'error',
                grouping: true,
                duration: 2000
            })
            // 使请求不进入正常的响应处理函数
            return Promise.reject(error)
        }
        // 网络不通，无法访问后端，或者服务器不在线
        if (error.code === 'ERR_NETWORK') {
            if (isCurrentServerRequest(error.config)) {
                serverStore.setStatus('offline', '无法访问服务器')
            }
            // 弹窗提示
            notifyRequestError(error, {
                message: error.config?.url === '/api/user/import/resume'
                    ? '无法连接服务端，简历导入结果尚未确认；请稍后刷新状态，勿重复上传。'
                    : '系统异常,请稍后重试',
                type: 'error',
                grouping: true,
                duration: 2000
            })
            // 使请求不进入正常的响应处理函数
            return Promise.reject(error)
        }

        // 获取后端返回的错误信息
        if (typeof error.response?.data?.message === "string" && error.response.data.message.trim()) {
            error.message = error.response.data.message;
        }

        if (error.response && error.response.status === 404) {
            error.message = "资源未找到";
        }

        // 弹窗提示，3秒
        notifyRequestError(error, {
            message: rateLimitMessage(error) || error.message || '服务端请求失败',
            type: 'error',
            grouping: true,
            duration: 3000
        })

        // 使请求不进入正常的响应处理函数
        return Promise.reject(error)
    })


/**
 * 暴露自定义的axios
 */
export default request;
