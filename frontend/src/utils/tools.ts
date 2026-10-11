import {Tools} from "../platform/utils";
import logger from "../logging";
import {LoginStore} from "../stores";
import axios from '../axios'
import {ElMessage as originalElMessage} from "element-plus";
import {LogRecorder} from "../logging/record";
import {GM_xmlhttpRequest} from "$";

const logRecorder = new LogRecorder();
let loginIng = false;


export const silentlyLogin = async (bossUserId: string) => {
    let loginCount = 0
    while (loginIng && loginCount < 6) {
        logger.info("login... ", loginCount)
        await Tools.sleep(500)
        loginCount++
    }
    if (loginIng) {
        throw new Error("登录请求仍在处理中")
    }
    loginIng = true;
    const loginStore = LoginStore();
    try {
        let token = Tools.window?._PAGE?.token;
        let count = 0;
        while (!token && count < 3) {
            await Tools.sleep(300);
            token = Tools.window?._PAGE?.token;
        }

        if (!token) {
            throw new Error("未登录 BOSS，静默登录失败");
        }
        if (!bossUserId) {
            bossUserId = Tools.window?._PAGE?.uid;
        }
        if (loginStore.login) {
            return;
        }

        const resp = await axios.post(
            "/api/user/silently/login?uniqueId=" + bossUserId,
            null,
            {headers: {"Zp_token": token}}
        );
        if (resp.data.code === 2000) {
            loginStore.loginFail();
            logRecorder.info("首次使用等待用户主动导入简历");
            originalElMessage.info({
                message: "首次使用不会自动上传简历。需要开通内测时，请点击“导入简历”；成功保存后自动开通，无需激活码。",
                duration: 7000,
                showClose: true
            });
            return;
        }
        if (resp.data.code !== 200 || !resp.data.data) {
            throw new Error("静默登录返回无效状态");
        }

        localStorage.setItem('Authorization', resp.data.data);
        loginStore.loginSuccess()
        logRecorder.info("静默登录成功")
    } catch (e: any) {
        logRecorder.error("静默登录失败", e?.name || "unknown")
        loginStore.loginFail()
        throw e;
    } finally {
        loginIng = false;
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
    const token = Tools.window?._PAGE?.token;
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

