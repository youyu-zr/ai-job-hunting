import {reactive, ref} from 'vue'
import {defineStore} from 'pinia'
import {User} from "./types";
import logger from "../logging";
import {TampermonkeyApi, Tools} from "../platform/utils";
import {userSnapshotStorageKey} from "./userSnapshotKey";
import {ServerStore} from "./server";
import {userSessionScope} from "../utils/sessionContext";

export const pushResultCount = defineStore('pushResultCount', () => {
    const notMatchCount = ref(0)
    const successCount = ref(TampermonkeyApi.GmGetValue(TampermonkeyApi.PUSH_SUCCESS_COUNT, 0))
    const onceSuccessCount = ref(0)
    const failCount = ref(TampermonkeyApi.GmGetValue(TampermonkeyApi.PUSH_FAIL_COUNT, 0))

    function notMatchIncr() {
        notMatchCount.value++
    }

    function successIncr() {
        successCount.value++
        onceSuccessCount.value++
        TampermonkeyApi.GmSetValue(TampermonkeyApi.PUSH_SUCCESS_COUNT, successCount.value)
    }

    function failIncr() {
        failCount.value++
        TampermonkeyApi.GmSetValue(TampermonkeyApi.PUSH_FAIL_COUNT, failCount.value)
    }

    function clearOnceSuccessCount() {
        onceSuccessCount.value = 0
    }

    return {
        notMatchIncr,
        successIncr,
        notMatchCount,
        successCount,
        failCount,
        failIncr,
        onceSuccessCount,
        clearOnceSuccessCount
    }
})

export const UserStore = defineStore('ai-user', () => {

    const platformType = ref<number>()
    const user = reactive<User>(getLocalUser())

    function switchAccount(accountId: string, serverUrl: string = ServerStore().baseUrl) {
        const accountUser = getLocalUser(accountId, serverUrl);
        Object.keys(user).forEach(key => delete (user as any)[key]);
        Object.assign(user, accountUser);
    }

    return {
        user,
        platformType,
        switchAccount
    };
})


export const LoginStore = defineStore('LoginStore', () => {

    const login = ref<false | true>()
    const loginFailStatus = ref<false | true>()
    const accountId = ref<string | null>(null)
    const accountScope = ref<string | null>(null)

    function loginSuccess(currentAccountId: string, currentScope = userSessionScope(currentAccountId, ServerStore().baseUrl)) {
        login.value = true
        accountId.value = currentAccountId
        accountScope.value = currentScope
        loginFailStatus.value = false
    }

    function loginFail() {
        login.value = false
        accountId.value = null
        accountScope.value = null
        loginFailStatus.value = true
    }

    function loginFailForScope(scope: string | null) {
        if (scope && accountScope.value === scope) {
            loginFail()
        }
    }

    function reset() {
        login.value = undefined
        accountId.value = null
        accountScope.value = null
        loginFailStatus.value = false
    }

    return {
        login, loginSuccess, loginFailStatus, loginFail, loginFailForScope, accountId, accountScope, reset
    };
})


function getLocalUser(
    accountId: string | number | null | undefined = Tools.window?._PAGE?.uid,
    serverUrl = ServerStore().baseUrl
): User {
    const serverStore = ServerStore();
    const snapshotKey = userSnapshotStorageKey(accountId, serverUrl);
    let jsonData = snapshotKey ? localStorage.getItem(snapshotKey) : null;
    if (jsonData === null && accountId !== undefined && accountId !== null && String(accountId).trim()) {
        const normalizedAccountId = String(accountId).trim();
        const mirrorKey = serverStore.getMirrorKey('user_config', normalizedAccountId, serverUrl);
        const mirroredUser = mirrorKey ? TampermonkeyApi.GmGetValue(mirrorKey, null) : null;
        if (mirroredUser && typeof mirroredUser === "object") {
            return mirroredUser as User;
        }
    }
    if (jsonData === null) {
        jsonData = '{"phone":"","email":"","preference":{},"preferenceMap":{}}'
    }
    let user: User;
    try {
        user = JSON.parse(jsonData) as User;
    } catch {
        user = {phone: "", email: "", preference: {}, preferenceMap: {}} as User;
    }
    logger.debug("获取本地用户配置", user)
    return user;
}


export const ProductStore = defineStore('ProductStore', () => {

    const showProduct = ref<false | true>(false)

    function setShowProduct(show: boolean) {
        showProduct.value = show
    }

    return {
        showProduct, setShowProduct
    };
})
