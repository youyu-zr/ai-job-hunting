<template>
    <!-- 服务器配置面板 -->
    <el-card class="server-config-card" shadow="hover">
        <div class="server-config-container">
            <div class="server-status">
                <el-badge :value="serverStore.isOnline ? '在线' : '离线'" :type="serverStore.isOnline ? 'success' : 'danger'">
                    <el-text size="large" strong>服务器状态</el-text>
                </el-badge>
                <el-tag type="info" effect="plain" style="margin-left: 8px;">版本：{{ scriptVersion }}</el-tag>
            </div>
            <div class="server-input">
                <el-input v-model="tempServerUrl" type="password" placeholder="连接地址已隐藏" autocomplete="off" class="custom-server-input">
                    <template #prepend>连接地址</template>
                    <template #append>
                        <el-button-group class="btn-group">
                            <el-button @click="handleUpdateServer" class="test-btn">连接测试</el-button>
                            <el-tooltip content="重置为默认地址" placement="top">
                                <el-button @click="handleResetServer" class="reset-btn">
                                    <el-icon><RefreshRight /></el-icon>
                                </el-button>
                            </el-tooltip>
                        </el-button-group>
                    </template>
                </el-input>
            </div>
            <div class="server-mode-tip">
                <el-tag :type="serverStore.isOnline ? 'success' : 'warning'" effect="dark">
                    {{ serverStore.isOnline ? '在线模式：功能全开' : '本地模式：AI功能受限' }}
                </el-tag>
            </div>
        </div>
    </el-card>

    <br>

    <el-text size="large" class="mx-1" type="primary">投递成功：{{
            pushResultCounter.successCount
        }}&nbsp;&nbsp;&nbsp;
    </el-text>
    <el-text size="large" class="mx-1" type="danger"> 投递失败：{{
            pushResultCounter.failCount
        }}&nbsp;&nbsp;&nbsp;
    </el-text>
    <el-text size="large" class="mx-1"> 单次投递限制数量：</el-text>
    <el-input-number v-model="selfDefPushCountLimit" :min="1" :max="100"
                     @change="selfDefPushCountLimitChange"/>
    <el-tag :type="resumeImportStatus === '成功导入简历' ? 'success' : 'info'" effect="plain" style="margin-left: 10px;">
        {{ resumeImportStatus }}
    </el-tag>
    <span v-if="!isProdEnv()">
        &nbsp;&nbsp;&nbsp;MOCK投递&nbsp; <el-switch v-model="mockPush"/>
    </span>
    <br>
    <br>

    <el-tooltip effect="dark" raw-content content="
    在Boss中更新了附件简历后请重新导入<p/>
    - 仅用于AI坐席定制化回复
    " placement="bottom">
        <el-button :icon="Upload as any" type="primary" @click="handlerImport"
                   :disabled="!serverStore.isOnline"
                   :loading="importResumeLoading"><p
            style="font-size: 15px">导入简历</p>
        </el-button>
    </el-tooltip>

    <el-tooltip effect="dark" raw-content content="
    先通过Boss的筛选功能圈选你的意向岗位<p/><span style='color:red;'>在【偏好设置-投递设置】中选择</span><br/>您的投递偏好，用于精准投递岗位
    " placement="bottom">
        <el-button :icon="Promotion as any" :type="pushBtnType" @click="handlerPush"><p style="font-size: 15px">
            {{ pushBtnText }}</p>
        </el-button>
    </el-tooltip>

    <el-tooltip effect="dark" raw-content content="
    AI坐席：登录并自动开通内测权限后可开启。<br/>
    - 开始投递和开启 AI 坐席前，请先导入本人简历。<br/>
    - 根据简历、求职要求和岗位信息回复HR。<br/>
    - HR索要简历时才通过平台发送简历。<br/>
    - 联系方式交换、明确面试安排及需要本人承诺的事项会暂停当前岗位会话。<br/>
    " placement="bottom">
        <el-button :icon="Service as any" color="#626aef" :disabled="!serverStore.isOnline">
            <p style="font-size: 15px">
                <span>AI坐席 </span>
                <el-switch active-text="开" inactive-text="关" inline-prompt
                           style="--el-switch-on-color: #13ce66; --el-switch-off-color: #ff4949"
                           :model-value="Boolean(userStore.user.aiSeatStatus)"
                           :disabled="!serverStore.isOnline"
                           @update:model-value="handlerAISeatStatusChange"/>
            </p>
        </el-button>
    </el-tooltip>

    <!-- 固定位置的投递记录与停止投递按钮 -->
    <div v-show="pushRecordsVisible" class="fixed-stop-button">
        <div class="push-records-container">
            <div class="push-records-header">
                <span>{{ pushStatus === PushStatus.PUSHING ? '实时投递记录' : '本次投递记录' }}</span>
                <el-tooltip v-if="pushStatus !== PushStatus.PUSHING" content="关闭投递记录" placement="top">
                    <el-button class="close-records-button" text circle aria-label="关闭投递记录" @click="closePushRecords">
                        <el-icon><CircleCloseFilled /></el-icon>
                    </el-button>
                </el-tooltip>
            </div>
            <div class="push-records-content">
                <div v-for="record in latestPushRecords" :key="record.eventId" class="push-record-item">
                    <span class="record-time">{{ record.timestamp }}</span>
                    <span class="record-message" :class="getRecordLevelClass(record.level)">
                        {{ record.message }}
                    </span>
                </div>
                <div v-if="latestPushRecords.length === 0" class="no-records">
                    暂无投递记录
                </div>
            </div>
        </div>

        <el-button v-show="pushStatus === PushStatus.PUSHING" type="warning" size="large" @click="handlerFixedStopPush">
            <el-icon><CircleCloseFilled /></el-icon>
            停止投递
        </el-button>
    </div>

</template>

<script setup lang="ts">
import axiosOriginal, {AxiosInstance} from "axios";
import {CircleCloseFilled, Promotion, Service, Upload, RefreshRight} from '../icons';
import {inject, ref, onUnmounted, watch} from "vue";
import {PushStatus} from "../../enums";
import {AbsPlatform} from "../../platform/platform";
import {Tools} from "../../platform/utils";
import {ElMessage, fetchWithGM_request, isProdEnv, loginInterceptor} from "../../utils/tools";
import logger from '../../logging'
import {LoginStore, pushResultCount, UserStore} from "../../stores";
import {ServerStore} from "../../stores/server";
import {ElNotification} from "element-plus";
import {LogRecorder} from "../../logging/record";
import {getLatestPushLogs, getRecordLevelClass} from "../../logging/queries";
import {serializeAiSeatStatus, shouldApplyAiSeatRollback} from "../../stores/seatState";
import {
    isApplicationSessionReady,
    isUserConfigReady,
    rememberAiSeatStatus,
    userRemoteLoad
} from "../../stores/remote";
import {waitForBossIdentity, INTERACTIVE_ATTEMPTS} from "../../utils/bossIdentity";
import type {BossIdentity} from "../../utils/bossIdentity";
import {userSessionScope} from "../../utils/sessionContext";
import {applicationResponseError, resumeImportErrorNotice} from "../../utils/resumeImportError";
import type {ResumeImportStage} from "../../utils/resumeImportError";

declare const GM_info: any;
declare const __APP_VERSION__: string;
const CURRENT_SCRIPT_VERSION = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : '0.0.0-unknown';
const platform = inject('$platform') as AbsPlatform;
const axios = inject('$axios') as AxiosInstance
const serverStore = ServerStore();
const tempServerUrl = ref(serverStore.baseUrl);
const scriptVersion = ref(typeof GM_info !== 'undefined' && GM_info?.script?.version ? GM_info.script.version : CURRENT_SCRIPT_VERSION);

const handleUpdateServer = async () => {
    const previousServerUrl = serverStore.baseUrl;
    serverStore.setBaseUrl(tempServerUrl.value);
    const serverChanged = serverStore.baseUrl !== previousServerUrl;
    if (serverChanged) {
        loginStore.reset();
        const accountId = String(Tools.window?._PAGE?.uid ?? "").trim();
        userStore.switchAccount(accountId, serverStore.baseUrl);
    }
    await serverStore.checkConnection();
    if (serverStore.isOnline) {
        if (serverChanged && String(Tools.window?._PAGE?.uid ?? "").trim()) {
            void userRemoteLoad();
        }
        ElNotification({
            title: '连接成功',
            message: serverChanged
                ? '已连接新服务器，正在加载当前账号配置。'
                : '已成功连接服务器；不会刷新页面，也不会重置 AI 坐席状态。',
            type: 'success',
            duration: 3000
        });
    } else {
        ElNotification({
            title: '连接失败',
            message: serverStore.lastError || '无法访问服务器',
            type: 'error',
            duration: 3000
        });
    }
};

const handleResetServer = async () => {
    if (typeof serverStore.resetBaseUrl === 'function') {
        tempServerUrl.value = 'https://aijob.zxpnb.top/';
        ElMessage.success('已重置为默认服务器地址');
        await handleUpdateServer();
    } else {
        // 容错处理
        const DEFAULT_URL = 'https://aijob.zxpnb.top/';
        tempServerUrl.value = DEFAULT_URL;
        ElMessage.success('已重置为默认服务器地址');
        await handleUpdateServer();
    }
};

const pushStatus = ref(PushStatus.NOT_START)
const pushBtnType = ref<'primary' | 'warning'>('primary')
const pushBtnText = ref<string>('开始投递')
const importResumeLoading = ref<boolean>(false);

// 创建日志记录器实例
const logRecorder = new LogRecorder();
const latestPushRecords = ref<{ eventId: string; level: string; message: string; timestamp: string }[]>([]);
const pushRecordsVisible = ref(false);
let recordsUpdateTimer: number | null = null;
let recordsSettleTimer: number | null = null;
let pushSessionStartEventId: string | null = null;

let loginStore = LoginStore();
let pushResultCounter = pushResultCount();

const userStore = UserStore();
const resumeImportStatus = ref(userStore.user.resumeId ? '成功导入简历' : '未成功导入简历');
let resumeImportAttempted = false;

const requireImportedResume = () => {
    if (userStore.user.resumeId) {
        return true;
    }
    ElMessage({
        type: 'warning',
        message: '请先在 BOSS 上传本人附件简历，再点击“导入简历”；完成后才能开始投递或开启 AI 坐席。',
        duration: 5000,
    });
    return false;
};

watch(() => userStore.user.resumeId, (resumeId) => {
    if (!resumeImportAttempted) {
        resumeImportStatus.value = resumeId ? '成功导入简历' : '未成功导入简历';
    }
});
// --------------------------------------------------函数定义-------------------------------------------------------------

// 获取最新的投递记录
const updateLatestPushRecords = () => {
    latestPushRecords.value = getLatestPushLogs(logRecorder, 10, pushSessionStartEventId);
};

// 获取记录级别的样式类
// 开始定时更新记录
const startRecordsUpdate = () => {
    if (recordsSettleTimer) {
        clearTimeout(recordsSettleTimer);
        recordsSettleTimer = null;
    }
    if (recordsUpdateTimer) {
        clearInterval(recordsUpdateTimer);
    }
    updateLatestPushRecords();
    // 每200ms更新一次
    recordsUpdateTimer = setInterval(updateLatestPushRecords, 500);
};

// 停止定时更新记录
const stopRecordsUpdate = () => {
    if (recordsUpdateTimer) {
        clearInterval(recordsUpdateTimer);
        recordsUpdateTimer = null;
    }
};

const stopRecordsAfterSettling = () => {
    if (recordsSettleTimer) {
        clearTimeout(recordsSettleTimer);
    }
    recordsSettleTimer = window.setTimeout(() => {
        updateLatestPushRecords();
        stopRecordsUpdate();
        recordsSettleTimer = null;
    }, 2500);
};

const closePushRecords = () => {
    pushRecordsVisible.value = false;
};

// 滚动到页面顶部
const scrollToTop = () => {
    window.scrollTo({
        top: 0,
        behavior: 'smooth'
    });
}

// --------------------------------------------------函数定义-------------------------------------------------------------


// --------------------------------------------------事件处理-------------------------------------------------------------
const handlerImport = async () => {
    if (importResumeLoading.value) {
        return;
    }
    importResumeLoading.value = true;
    const expectedAccountId = String(Tools.window?._PAGE?.uid ?? "").trim();
    const requestedServerUrl = serverStore.baseUrl;
    let identity: BossIdentity | undefined;
    let stage: ResumeImportStage = "identity";
    const currentBossToken = () =>
        String(Tools.getCookieValue("bst") ?? "").trim();
    const contextIsCurrent = () =>
        serverStore.baseUrl === requestedServerUrl
        && (!identity
            ? !expectedAccountId || String(Tools.window?._PAGE?.uid ?? "").trim() === expectedAccountId
            : String(Tools.window?._PAGE?.uid ?? "").trim() === identity.accountId
                && currentBossToken() === identity.token);

    try {
        identity = await waitForBossIdentity(
            () => Tools.window?._PAGE,
            expectedAccountId,
            // The user is waiting on the button they just pressed, so this path gets a longer
            // budget than the reactive loads: a slow page must not turn their click into a failure.
            INTERACTIVE_ATTEMPTS,
            undefined,
            currentBossToken,
            () => Tools.window?._PAGE,
        );
        if (!contextIsCurrent()) {
            return;
        }
        const {accountId: bossUserId, token} = identity;
        if (!userSessionScope(bossUserId, requestedServerUrl)) {
            throw Object.assign(new Error("当前账号或服务器地址无效"), {code: "RESUME_SCOPE_INVALID"});
        }
        // The import endpoint authorizes with the application session, not with the BOSS
        // credential, so a rotated cookie is not a reason to re-login or to refuse the import.
        if (!isApplicationSessionReady()) {
            await userRemoteLoad();
        }
        if (!isApplicationSessionReady()) {
            throw Object.assign(new Error("AI 工作猎手账号尚未连接完成"), {code: "APP_SESSION_NOT_READY"});
        }
        if (!contextIsCurrent()) {
            return;
        }
        resumeImportAttempted = true;
        resumeImportStatus.value = '未成功导入简历';
        stage = "lookup";
        const resumeInfoResp = await axiosOriginal.get("https://www.zhipin.com/wapi/zpgeek/resume/sidebar.json", {headers: {"Zp_token": token}} as {});
        if (!contextIsCurrent()) {
            return;
        }
        const zpData = resumeInfoResp.data?.zpData;
        if (!Array.isArray(zpData?.attachmentList)) {
            throw new Error("附件列表响应无效");
        }
        if (zpData.attachmentList.length === 0) {
            throw Object.assign(new Error("未找到附件简历"), {code: "RESUME_ATTACHMENT_MISSING"});
        }
        const resumeId = String(zpData.attachmentList[0]?.resumeId ?? "").trim();
        if (!resumeId) {
            throw new Error("附件简历标识无效");
        }
        stage = "download";
        const resumeFileResp: any = await fetchWithGM_request(
            "https://docdownload.zhipin.com/wflow/zpgeek/download/download4geek?resumeId=" + resumeId,
            {headers: {"Zp_token": token}, responseType: 'arraybuffer'} as {},
        );
        if (!contextIsCurrent()) {
            return;
        }
        if (resumeFileResp?.status !== 200
            || Object.prototype.toString.call(resumeFileResp.response) !== "[object ArrayBuffer]"
            || resumeFileResp.response.byteLength === 0) {
            throw Object.assign(new Error("附件下载未返回有效文件"), {status: resumeFileResp?.status});
        }
        const fileBlob = new Blob([resumeFileResp.response], {type: 'application/pdf'});
        const formData = new FormData();
        formData.append("file", fileBlob);
        formData.append("resumeId", resumeId);
        stage = "upload";
        const importResp = await axios.post("/api/user/import/resume", formData, {
            timeout: 120000,
            headers: {'Content-Type': "multipart/form-data"},
            bossIdentityContext: {...identity, serverUrl: requestedServerUrl},
        });
        if (!contextIsCurrent()) {
            return;
        }
        if (importResp.data.code !== 200) {
            throw applicationResponseError(importResp);
        }
        let loginSessionReady = false;
        try {
            await userRemoteLoad();
            loginSessionReady = isUserConfigReady() && String(userStore.user.resumeId ?? "") === resumeId;
        } catch {
            logger.warn('简历已保存，但登录状态刷新失败');
        }
        if (!contextIsCurrent()) {
            return;
        }
        userStore.user.resumeId = resumeId;
        resumeImportStatus.value = '成功导入简历';
        const refreshWarning = loginSessionReady ? '' : '；请刷新页面重新连接';
        const emailWarning = importResp.data?.data?.email
            ? ''
            : '；未识别到邮箱，请在偏好设置中完善通知邮箱';
        if (!importResp.data?.data?.email) {
            resumeImportStatus.value = '成功导入简历';
        }
        ElMessage({
            message: `简历保存成功，内测权限已自动开通${refreshWarning}${emailWarning}`,
            type: loginSessionReady && importResp.data?.data?.email ? 'success' : 'warning',
            duration: 3000
        });
    } catch (error: any) {
        if (!contextIsCurrent()) {
            return;
        }
        resumeImportStatus.value = '未成功导入简历';
        const httpStatus = Number.isInteger(error?.response?.status) ? error.response.status : undefined;
        const businessCode = Number.isInteger(error?.response?.data?.code) ? error.response.data.code : undefined;
        logger.error('导入简历失败', stage, httpStatus, businessCode);
        const message = resumeImportErrorNotice(error, stage);
        if (message) {
            ElMessage({
                message,
                type: 'error',
                duration: 5000
            });
        }
    } finally {
        importResumeLoading.value = false;
    }
}
const handlerPush = () => {
    switch (pushStatus.value) {
        case PushStatus.NOT_START:
            startPush();
            break;
        case PushStatus.PUSHING:
            pausePush();
            break;
        case PushStatus.PAUSE:
            startPush()
            break;
    }
}

// 固定按钮停止投递处理
const handlerFixedStopPush = () => {
    pausePush();
    scrollToTop();
}

/**
 * 单次投递次数限制
 */
const selfDefPushCountLimit = ref<number>(platform.selfDefPushCountLimit);
const selfDefPushCountLimitChange = (val: number) => {
    platform.selfDefPushCountLimit = val;
}

// 非生产环境支持mock投递
const mockPush = ref<boolean>(false)

const startPush = () => {

    if (!loginInterceptor()) {
        return;
    }
    if (!requireImportedResume()) {
        return;
    }

    platform.pushMock = mockPush.value

    pushSessionStartEventId = logRecorder.getLatestEventId();
    latestPushRecords.value = [];
    pushRecordsVisible.value = true;

    pushStatus.value = PushStatus.PUSHING
    pushBtnType.value = 'warning'
    pushBtnText.value = '停止投递'

    // 开始更新本次投递记录
    startRecordsUpdate();

    let pushResultPromise = platform.startPush();

    //   投递结果处理
    pushResultPromise.then(() => {
        ElMessage({
            message: "批量投递完成",
            type: 'success',
            duration: 3000
        })
        setTimeout(() => {
            updateLatestPushRecords();
            pushStatus.value = PushStatus.PAUSE;
            pushBtnType.value = 'primary'
            pushBtnText.value = '开始投递'
            // 给 websocket 回执留出时间，再停止本次投递记录刷新。
            stopRecordsAfterSettling();
        }, 200)
    })
}
const pausePush = () => {
    platform.pausePush()
    pushStatus.value = PushStatus.PAUSE;
    pushBtnType.value = 'primary'
    pushBtnText.value = '开始投递'
    // 停止更新投递记录
    stopRecordsUpdate();
    if (recordsSettleTimer) {
        clearTimeout(recordsSettleTimer);
        recordsSettleTimer = null;
    }
    window.setTimeout(updateLatestPushRecords, 0);
}

const handlerAISeatStatusChange = async (val: boolean) => {
    const previousAiSeatStatus = serializeAiSeatStatus(userStore.user.aiSeatStatus);
    const nextAiSeatStatus = serializeAiSeatStatus(val);

    if (val && !requireImportedResume()) {
        return;
    }

    if (!loginInterceptor()) {
        return;
    }

    userStore.user.aiSeatStatus = nextAiSeatStatus;
    const operationRevision = rememberAiSeatStatus(Boolean(nextAiSeatStatus), userStore.user);

    return axios.post("/api/user/save/preference", {
        aiSeatStatus: nextAiSeatStatus
    }).then(async resp => {
        if (resp?.data?.code && resp.data.code !== 200) {
            throw new Error(resp.data.message || 'AI seat switch failed');
        }
        if (val) {
            await axios.post('/api/job/seeker/cloned/change/session/user/resume');
        }
        if (val && resp.data.message && resp.data.message !== "成功") {
            ElNotification({
                message: resp.data.message,
                type: 'success',
                duration: 2000
            });
        }
    }).catch((error: any) => {
        if (shouldApplyAiSeatRollback(operationRevision)) {
            userStore.user.aiSeatStatus = previousAiSeatStatus
            rememberAiSeatStatus(Boolean(previousAiSeatStatus), userStore.user);
        }
        const message = error?.response?.data?.message || error?.message || '';
        if (message.includes('请先导入简历')) {
            ElMessage({
                type: 'warning',
                message: '请先导入本人简历，再开启 AI 坐席。',
                duration: 5000,
            });
        } else {
            ElMessage({
                type: 'error',
                message: message || 'AI 坐席状态保存失败，请检查连接后重试。',
                duration: 4000,
            });
        }
    })
}


// --------------------------------------------------事件处理-------------------------------------------------------------

// --------------------------------------------------流程处理-------------------------------------------------------------

// 组件卸载时清理定时器
onUnmounted(() => {
    stopRecordsUpdate();
    if (recordsSettleTimer) {
        clearTimeout(recordsSettleTimer);
        recordsSettleTimer = null;
    }
});

// --------------------------------------------------流程处理-------------------------------------------------------------
</script>

<style scoped>
.server-config-card {
    margin-bottom: 20px;
    background: rgba(255, 255, 255, 0.8);
    backdrop-filter: blur(10px);
    border-radius: 12px;
}

.server-config-container {
    display: flex;
    align-items: center;
    gap: 20px;
    flex-wrap: wrap;
}

.server-status {
    min-width: 120px;
}

.server-input {
    flex: 1;
    min-width: 450px;
}

:deep(.custom-server-input .el-input-group__prepend) {
    width: 100px;
    text-align: center;
    padding: 0 10px;
}

:deep(.custom-server-input .el-input-group__append) {
    padding: 0;
    width: 140px;
}

:deep(.custom-server-input .el-input-group__append .btn-group) {
    display: flex;
    height: 100%;
    width: 100%;
}

:deep(.custom-server-input .el-input-group__append .el-button) {
    border: none;
    margin: 0;
    height: 100%;
    flex: 1;
    border-radius: 0;
    display: flex;
    justify-content: center;
    align-items: center;
}

:deep(.custom-server-input .el-input-group__append .test-btn) {
    padding: 0 10px;
    border-right: 1px solid #dcdfe6;
    flex: 2;
}

:deep(.custom-server-input .el-input-group__append .reset-btn) {
    padding: 0;
    border-radius: 0 4px 4px 0;
    flex: 1;
    min-width: 40px;
}

.server-mode-tip {
    margin-left: auto;
}

.my-header {
    display: flex;
    flex-direction: row;
    justify-content: space-between;
    gap: 16px;
}

/* 固定位置的停止投递按钮样式 */
.fixed-stop-button {
    position: fixed;
    right: 80px;
    bottom: 80px;
    z-index: 9999;
    width: min(420px, calc(100vw - 32px));
    box-sizing: border-box;
    background: rgba(255, 255, 255, 0.95);
    padding: 8px;
    border-radius: 8px;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
    backdrop-filter: blur(10px);
    border: 1px solid rgba(255, 255, 255, 0.3);
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
}

.fixed-stop-button:hover {
    background: rgba(255, 255, 255, 1);
    box-shadow: 0 6px 16px rgba(0, 0, 0, 0.3);
}

/* 投递记录容器样式 */
.push-records-container {
    width: 100%;
    height: 240px;
    box-sizing: border-box;
    background: rgba(255, 255, 255, 0.9);
    border-radius: 6px;
    border: 1px solid rgba(0, 0, 0, 0.1);
    overflow: hidden;
    display: flex;
    flex-direction: column;
}

.recent-push-records {
    margin-top: 16px;
}

.push-records-header {
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    color: white;
    min-height: 38px;
    box-sizing: border-box;
    padding: 8px 12px;
    font-size: 14px;
    font-weight: 500;
    text-align: center;
    display: flex;
    align-items: center;
    justify-content: center;
    position: relative;
}

.push-records-content {
    flex: 1 1 auto;
    min-height: 0;
    overflow-y: auto;
    padding: 8px;
}

.push-record-item {
    display: flex;
    flex-direction: column;
    min-width: 0;
    margin-bottom: 8px;
    padding: 6px 8px;
    background: rgba(248, 250, 252, 0.8);
    border-radius: 4px;
    border-left: 3px solid #e2e8f0;
    font-size: 12px;
    line-height: 1.4;
}

.push-record-item:last-child {
    margin-bottom: 0;
}

.record-time {
    color: #64748b;
    font-size: 11px;
    margin-bottom: 2px;
}

.record-message {
    display: block;
    min-width: 0;
    color: #334155;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    word-break: break-word;
}

.record-error {
    color: #dc2626;
    border-left-color: #dc2626;
}

.record-warn {
    color: #d97706;
    border-left-color: #d97706;
}

.record-info {
    color: #2563eb;
    border-left-color: #2563eb;
}

.record-debug {
    color: #059669;
    border-left-color: #059669;
}

.record-trace {
    color: #7c3aed;
    border-left-color: #7c3aed;
}

.no-records {
    text-align: center;
    color: #94a3b8;
    font-size: 12px;
    padding: 20px 0;
}

.fixed-stop-button > :deep(.el-button) {
    width: 100%;
    min-height: 40px;
    margin: 0;
}

.close-records-button {
    position: absolute;
    right: 4px;
    top: 50%;
    transform: translateY(-50%);
    color: white;
}

@media (max-width: 600px) {
    .fixed-stop-button {
        right: 16px;
        bottom: 16px;
        width: calc(100vw - 32px);
    }
}

/* 滚动条样式 */
.push-records-content::-webkit-scrollbar {
    width: 4px;
}

.push-records-content::-webkit-scrollbar-track {
    background: rgba(0, 0, 0, 0.05);
    border-radius: 2px;
}

.push-records-content::-webkit-scrollbar-thumb {
    background: rgba(0, 0, 0, 0.2);
    border-radius: 2px;
}

.push-records-content::-webkit-scrollbar-thumb:hover {
    background: rgba(0, 0, 0, 0.3);
}
</style>
