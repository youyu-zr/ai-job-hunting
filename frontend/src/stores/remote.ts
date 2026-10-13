import {LoginStore, UserStore} from "./index";
import {PreferenceConfig} from "./types";
import {ProductStore} from "./index";
import {ServerStore} from "./server";
import {TampermonkeyApi, Tools} from "../platform/utils";
import logging from "../logging";
import {ElMessage, silentlyLogin} from "../utils/tools"
import axios from "../axios";
import {LogRecorder} from "../logging/record";
import {
    getAiSeatStatusRevision,
    markAiSeatStatusMutation,
    normalizeAiSeatStatus,
    resolveRemoteAiSeatStatus
} from "./seatState";
import {applyPersonalPreferenceDefaults} from "./preferenceDefaults";
import {userSnapshotStorageKey} from "./userSnapshotKey";
import {createScopedReadiness} from "./scopedReadiness";
import {scopedAuthorization, userSessionScope} from "../utils/sessionContext";
import {BossIdentity, waitForBossIdentity} from "../utils/bossIdentity";
import {captureUserConfigSnapshot, mergeRemoteUserConfig, UserConfigSnapshot} from "./profileMerge";

const logRecorder = new LogRecorder();
const DEFAULT_RISK_PACK_VERSION = "risk-pack-20260829-v2";
const userConfigReadiness = createScopedReadiness();
let identityRetryTimer: ReturnType<typeof setTimeout> | undefined;
let identityRetryCount = 0;

export function isUserConfigReady(): boolean {
    const scope = currentSessionScope();
    const loginStore = LoginStore();
    return Boolean(scope
        && userConfigReadiness.isReady(scope)
        && loginStore.login
        && loginStore.accountScope === scope
        && !loginStore.loginFailStatus);
}

export function waitForUserConfigReady(timeoutMs = 12000): Promise<boolean> {
    const scope = currentSessionScope();
    if (!scope) {
        return Promise.resolve(false);
    }
    if (isUserConfigReady()) {
        return Promise.resolve(true);
    }
    return userConfigReadiness.wait(scope, timeoutMs)
        .then(ready => ready && isUserConfigReady());
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
    return uid === undefined || uid === null ? '' : String(uid).trim();
}

function currentSessionScope(): string | null {
    return userSessionScope(currentBossAccountId(), ServerStore().baseUrl);
}

function persistUserSnapshot(
    user: any,
    accountId = currentBossAccountId(),
    serverUrl = ServerStore().baseUrl
) {
    const snapshotKey = userSnapshotStorageKey(accountId, serverUrl);
    const mirrorKey = ServerStore().getMirrorKey('user_config', accountId, serverUrl);
    if (!accountId || !snapshotKey || !mirrorKey) {
        return;
    }
    TampermonkeyApi.GmSetValue(mirrorKey, user);
    localStorage.setItem(snapshotKey, JSON.stringify(user));
}

function applyPreferenceDefaults(preference: PreferenceConfig): void {
    preference.pi = preference.pi || 3;
    preference.npi = preference.npi || 6;
    preference.locationGuardE = preference.locationGuardE ?? false;
    preference.resumeCity = preference.resumeCity || '';
    preference.targetCities = Array.isArray(preference.targetCities)
        ? preference.targetCities
        : [];
    preference.locationMismatchThreshold = preference.locationMismatchThreshold || 2;
    preference.communicationStyle = preference.communicationStyle || 'NATURAL';
    preference.afE = preference.afE ?? true;
    preference.jceE = preference.jceE ?? false;
    preference.jce = Array.isArray(preference.jce) ? preference.jce : [];
    preference.drpV = preference.drpV || DEFAULT_RISK_PACK_VERSION;
    applyPersonalPreferenceDefaults(preference);
}

export function rememberAiSeatStatus(status: boolean, user?: any): number {
    const revision = markAiSeatStatusMutation();
    if (user) {
        user.aiSeatStatus = status;
        persistUserSnapshot(user);
    }
    return revision;
}

type UserConfigRequestContext = {
    accountId: string;
    serverUrl: string;
    scope: string;
    baseline: UserConfigSnapshot;
}

function applyUserConfig(
    incomingUser: any,
    requestStartedAt: number,
    context: UserConfigRequestContext,
    options: UserRemoteLoadOptions = {}
): boolean {
    if (currentSessionScope() !== context.scope) {
        return false;
    }
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

    Object.assign(
        userStore.user,
        mergeRemoteUserConfig(userStore.user, incomingUser, context.baseline)
    );
    if (!userStore.user.preference) {
        userStore.user.preference = {} as PreferenceConfig;
    }
    applyPreferenceDefaults(userStore.user.preference);
    if (nextAiSeatStatus !== undefined) {
        userStore.user.aiSeatStatus = nextAiSeatStatus as any;
    }

    persistUserSnapshot(userStore.user, context.accountId, context.serverUrl);
    return true;
}

export async function userRemoteLoad(options: UserRemoteLoadOptions = {}): Promise<void> {
    logRecorder.info("load user preference config");
    const userStore = UserStore();
    const loginStore = LoginStore();
    const serverStore = ServerStore();
    const requestStartedAt = getAiSeatStatusRevision();
    const requestedServerUrl = serverStore.baseUrl;
    let identity: BossIdentity;
    try {
        identity = await waitForBossIdentity(() => Tools.window?._PAGE);
        identityRetryCount = 0;
        if (identityRetryTimer) {
            clearTimeout(identityRetryTimer);
            identityRetryTimer = undefined;
        }
    } catch (error: any) {
        logRecorder.warn("BOSS身份尚未就绪，未向服务端发送建档请求", error?.message || error);
        let retryScheduled = Boolean(identityRetryTimer);
        if (identityRetryCount < 3 && !identityRetryTimer) {
            identityRetryCount += 1;
            retryScheduled = true;
            identityRetryTimer = setTimeout(() => {
                identityRetryTimer = undefined;
                void userRemoteLoad(options);
            }, 5000);
        }
        ElMessage.info({
            message: retryScheduled
                ? "暂时读取不到 BOSS 登录状态，正在稍后重试。请确认已登录 BOSS；这一步不需要导入简历。"
                : "暂时读取不到 BOSS 登录状态，账号尚未创建。请确认已登录 BOSS 后刷新页面；这一步不需要导入简历。",
            duration: 7000,
            showClose: true
        });
        return;
    }
    if (serverStore.baseUrl !== requestedServerUrl) {
        return;
    }
    const requestedAccountId = identity.accountId;
    const requestedScope = userSessionScope(requestedAccountId, requestedServerUrl);
    if (!requestedScope) {
        loginStore.loginFail();
        return;
    }
    let requestContext: UserConfigRequestContext = {
        accountId: requestedAccountId,
        serverUrl: requestedServerUrl,
        scope: requestedScope,
        baseline: captureUserConfigSnapshot(userStore.user)
    };
    const contextIsCurrent = () => currentSessionScope() === requestedScope;
    userConfigReadiness.begin(requestedScope);
    let profileLoaded = false;

    try {
        if (loginStore.accountScope !== requestedScope) {
            userStore.switchAccount(requestedAccountId, requestedServerUrl);
        }
        requestContext = {
            ...requestContext,
            baseline: captureUserConfigSnapshot(userStore.user)
        };
        const loginOutcome = await silentlyLogin(requestedAccountId, identity.token);
        if (!contextIsCurrent()) {
            return;
        }
        if (loginOutcome === "legacy-registration-required") {
            if (!userStore.user.preference) {
                userStore.user.preference = {} as PreferenceConfig;
            }
            applyPreferenceDefaults(userStore.user.preference);
            logRecorder.warn("服务端尚未支持首次登录自动建档");
            return;
        }

        logging.debug("load user preference config from server");
        const resp = await axios.post("/api/user/userinfo", {});
        if (!contextIsCurrent()) {
            return;
        }
        profileLoaded = applyUserConfig(resp?.data?.data, requestStartedAt, requestContext, options);

        const betaResp = await axios.get("/api/beta/access").catch(error => {
            logRecorder.warn("beta access load failed", error?.message || error);
            return null;
        });
        if (!contextIsCurrent()) {
            return;
        }
        const betaAccess = betaResp?.data?.data;
        if (betaAccess?.activationEnabled && !betaAccess?.accessActive && !betaAccess?.betaActivated) {
            ProductStore().setShowProduct(true);
        }
        logRecorder.info("loaded server config");
    } catch (error: any) {
        logRecorder.warn("server config load failed; try local mirror", error?.message || error);

        if (!contextIsCurrent()) {
            return;
        }
        const mirrorKey = serverStore.getMirrorKey('user_config', requestedAccountId, requestedServerUrl);
        const mirrorData = mirrorKey ? TampermonkeyApi.GmGetValue(mirrorKey, null) : null;

        if (mirrorData) {
            profileLoaded = applyUserConfig(mirrorData, requestStartedAt, requestContext, options);
            logRecorder.info("loaded local mirror config");
        } else {
            if (!scopedAuthorization(requestedScope)) {
                loginStore.loginFail();
            }
            logRecorder.error("load config failed: no server data and no local mirror");
        }
    } finally {
        if (contextIsCurrent()) {
            if (!userStore.user.preference) {
                userStore.user.preference = {} as PreferenceConfig;
            }
            const localSeatStatus = normalizeAiSeatStatus(userStore.user.aiSeatStatus);
            if (localSeatStatus !== undefined) {
                userStore.user.aiSeatStatus = localSeatStatus as any;
            }
        }
        const authenticated = loginStore.login
            && loginStore.accountScope === requestedScope
            && !loginStore.loginFailStatus;
        userConfigReadiness.complete(requestedScope, Boolean(contextIsCurrent() && profileLoaded && authenticated));
    }
}

