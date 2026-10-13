import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { TampermonkeyApi } from "../platform/utils"
import {userSessionScope} from "../utils/sessionContext";

const SERVER_URL_KEY = 'custom_server_url_v2'
export const DEFAULT_SERVER_URL = 'https://aijob.zxpnb.top/'

export const ServerStore = defineStore('server', () => {
    // 从 GM_getValue 获取保存的地址，如果没有则使用默认地址
    const baseUrl = ref(TampermonkeyApi.GmGetValue(SERVER_URL_KEY, DEFAULT_SERVER_URL))
    const status = ref<'online' | 'offline' | 'checking'>('checking')
    const lastError = ref('')
    let reconnectTimer: ReturnType<typeof setInterval> | undefined

    // 计算属性：当前是否为在线模式
    const isOnline = computed(() => status.value === 'online')

    /**
     * 更新服务器地址并持久化
     */
    function setBaseUrl(url: string) {
        if (!url.endsWith('/')) {
            url += '/'
        }
        baseUrl.value = url
        TampermonkeyApi.GmSetValue(SERVER_URL_KEY, url)
        status.value = 'checking'
    }

    /**
     * 重置为默认服务器地址
     */
    function resetBaseUrl() {
        setBaseUrl(DEFAULT_SERVER_URL)
    }

    /**
     * 设置当前状态
     */
    function setStatus(newStatus: 'online' | 'offline' | 'checking', error: string = '') {
        status.value = newStatus
        lastError.value = error
    }

    /**
     * 获取本地镜像 Key。
     *
     * 必须同时绑定服务器地址和当前 BOSS 账号：只绑定服务器时，同一台机器上
     * 切换 BOSS 账号会直接读到上一个账号的简历、偏好和坐席状态，属于账号串号。
     */
    function getMirrorKey(type: string, accountId?: string | number, serverUrl = baseUrl.value): string | null {
        const scope = userSessionScope(accountId, serverUrl)
        return scope ? `mirror_${type}_${scope}` : null
    }


    /**
     * 测试连接状态
     */
    async function checkConnection() {
        status.value = 'checking'
        const requestedUrl = baseUrl.value
        try {
            const response = await fetch(`${requestedUrl}api/user/userinfo`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({})
            })
            if (baseUrl.value !== requestedUrl) {
                return
            }
            if (response.ok || response.status === 401) {
                status.value = 'online'
                lastError.value = ''
            } else {
                status.value = 'offline'
                lastError.value = `HTTP ${response.status}`
            }
        } catch (e) {
            if (baseUrl.value !== requestedUrl) {
                return
            }
            status.value = 'offline'
            lastError.value = '网络连接失败'
        }
    }

    /**
     * 离线/检查中时低频自动重连，避免一次瞬时网络失败后长期停留本地模式。
     */
    function startAutoReconnect() {
        if (reconnectTimer) {
            return
        }
        reconnectTimer = setInterval(() => {
            if (status.value !== 'online') {
                void checkConnection()
            }
        }, 60000)
    }

    return {
        baseUrl,
        status,
        lastError,
        isOnline,
        setBaseUrl,
        resetBaseUrl,
        setStatus,
        getMirrorKey,
        checkConnection,
        startAutoReconnect
    }
})
