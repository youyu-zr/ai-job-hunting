import {LoginStore, UserStore} from "./index";
import {PreferenceConfig} from "./types";
import {ProductStore} from "./index";
import {ServerStore} from "./server";
import {TampermonkeyApi, Tools} from "../platform/utils";
import logging from "../logging";
import {silentlyLogin} from "../utils/tools"
import axios from "../axios";
import {LogRecorder} from "../logging/record";
import {
    getAiSeatStatusRevision,
    markAiSeatStatusMutation,
    normalizeAiSeatStatus,
    resolveRemoteAiSeatStatus
} from "./seatState";
import {applyPersonalPreferenceDefaults} from "./preferenceDefaults";

const logRecorder = new LogRecorder();
const LOCAL_USER_KEY = "ai-job-user";
const DEFAULT_RISK_PACK_VERSION = "risk-pack-20260829-v2";

let userConfigLoaded = false;
let resolveUserConfigReady!: () => void;
const userConfigReady = new Promise<void>((resolve) => {
    resolveUserConfigReady = resolve;
});

export function isUserConfigReady(): boolean {
    return userConfigLoaded;
}

export function waitForUserConfigReady(timeoutMs = 12000): Promise<boolean> {
    if (userConfigLoaded) {
        return Promise.resolve(true);
    }
    return Promise.race([
        userConfigReady.then(() => true),
        new Promise<boolean>((resolve) => window.setTimeout(() => resolve(false), timeoutMs)),
    ]);
}

function markUserConfigReady(): void {
    if (userConfigLoaded) {
        return;
    }
    userConfigLoaded = true;
    resolveUserConfigReady();
}

type UserRemoteLoadOptions = {
    preserveLocalAiSeatStatus?: boolean
}

/**
 * 当前 BOSS 账号标识。
 *
 * 镜像必须按账号隔离写入；拿不到账号时不写入带账号的 key，
 * 避免把 A 账号的简历/偏好写进 B 账号的读取范围。
 */
function currentBossAccountId(): string {
    const uid = Tools.window?._PAGE?.uid;
    return uid === undefined || uid === null ? '' : String(uid);
}

function persistUserSnapshot(user: any, serverStore = ServerStore()) {
    const accountId = currentBossAccountId();
    if (!accountId) {
        // 账号未知时不写镜像：宁可本次不落盘，也不跨账号串数据。
        localStorage.setItem(LOCAL_USER_KEY, JSON.stringify(user));
        return;
    }
    const mirrorKey = serverStore.getMirrorKey('user_config', accountId);
    TampermonkeyApi.GmSetValue(mirrorKey, user);
    localStorage.setItem(LOCAL_USER_KEY, JSON.stringify(user));
}

export function rememberAiSeatStatus(status: boolean, user?: any): number {
    const revision = markAiSeatStatusMutation();
    if (user) {
        user.aiSeatStatus = status;
        persistUserSnapshot(user);
    }
    return revision;
}

function applyUserConfig(incomingUser: any, requestStartedAt: number, options: UserRemoteLoadOptions = {}) {
    const userStore = UserStore();
    if (!incomingUser) {
        throw new Error("user config is empty");
    }

    const nextAiSeatStatus = resolveRemoteAiSeatStatus(
        incomingUser.aiSeatStatus,
        userStore.user.aiSeatStatus,
        getAiSeatStatusRevision(),
        requestStartedAt,
        options.preserveLocalAiSeatStatus !== false
    );

    Object.assign(userStore.user, incomingUser);
    if (!userStore.user.preference) {
        userStore.user.preference = {} as PreferenceConfig;
    }
    userStore.user.preference.pi = userStore.user.preference.pi || 3;
    userStore.user.preference.npi = userStore.user.preference.npi || 6;
    userStore.user.preference.locationGuardE = userStore.user.preference.locationGuardE ?? false;
    userStore.user.preference.resumeCity = userStore.user.preference.resumeCity || '';
    userStore.user.preference.targetCities = Array.isArray(userStore.user.preference.targetCities)
        ? userStore.user.preference.targetCities
        : [];
    userStore.user.preference.locationMismatchThreshold = userStore.user.preference.locationMismatchThreshold || 2;
    userStore.user.preference.communicationStyle = userStore.user.preference.communicationStyle || 'NATURAL';
    userStore.user.preference.afE = userStore.user.preference.afE ?? true;
    userStore.user.preference.jceE = userStore.user.preference.jceE ?? false;
    userStore.user.preference.jce = Array.isArray(userStore.user.preference.jce)
        ? userStore.user.preference.jce
        : [];
    userStore.user.preference.drpV = userStore.user.preference.drpV || DEFAULT_RISK_PACK_VERSION;
    applyPersonalPreferenceDefaults(userStore.user.preference);
    if (nextAiSeatStatus !== undefined) {
        userStore.user.aiSeatStatus = nextAiSeatStatus as any;
    }

    persistUserSnapshot(userStore.user);
}

export function userRemoteLoad(options: UserRemoteLoadOptions = {}) {
    logRecorder.info("load user preference config");
    const userStore = UserStore();
    const loginStore = LoginStore();
    const serverStore = ServerStore();
    const requestStartedAt = getAiSeatStatusRevision();

    if (loginStore.loginFailStatus) {
        return Promise.resolve();
    }

    return silentlyLogin("").then(_ => {
        logging.debug("load user preference config from server");
        return axios.post("/api/user/userinfo", {});
    }).then(resp => {
        applyUserConfig(resp?.data?.data, requestStartedAt, options);
        return axios.get("/api/beta/access").catch(error => {
            logRecorder.warn("beta access load failed", error?.message || error);
            return null;
        });
    }).then(resp => {
        const betaAccess = resp?.data?.data;
        if (betaAccess?.activationEnabled && !betaAccess?.accessActive && !betaAccess?.betaActivated) {
            ProductStore().setShowProduct(true);
        }
        logRecorder.info("loaded server config");
    }).catch(error => {
        logRecorder.warn("server config load failed; try local mirror", error?.message || error);

        // 只读当前账号自己的镜像；账号未知时不回退到任何共享镜像。
        const accountId = currentBossAccountId();
        const mirrorData = accountId
            ? TampermonkeyApi.GmGetValue(serverStore.getMirrorKey('user_config', accountId), null)
            : null;

        if (mirrorData) {
            applyUserConfig(mirrorData, requestStartedAt, options);
            logRecorder.info("loaded local mirror config");
        } else {
            loginStore.loginFail();
            logRecorder.error("load config failed: no server data and no local mirror");
        }
    }).finally(() => {
        if (!userStore.user.preference) {
            userStore.user.preference = {} as PreferenceConfig;
        }
        const localSeatStatus = normalizeAiSeatStatus(userStore.user.aiSeatStatus);
        if (localSeatStatus !== undefined) {
            userStore.user.aiSeatStatus = localSeatStatus as any;
        }
        markUserConfigReady();
    });
}

