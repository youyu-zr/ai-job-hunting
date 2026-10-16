<template>
    <br/>
    <div class="ai-seat-toolbar">
        <el-tag class="ai-seat-status" :type="seatStatusTagType" effect="dark">
            <span class="ai-seat-status-dot" :class="`status-${seatStatus.status.toLowerCase()}`"></span>
            {{ seatStatus.label }}
        </el-tag>
        <el-button
            v-if="seatStatus.manuallyPaused"
            style="margin-left: 10px;"
            type="success"
            @click="handlerClick"
        >恢复当前会话</el-button>
    </div>
</template>

<script setup lang="ts">
import {AiPower} from "../../platform/aiPower";
import {ElMessage} from "../../utils/tools";
import {BossOption} from "../../platform/bossPlatform";
import {computed, onMounted, onUnmounted, ref} from "vue";
import {UserStore} from "../../stores";
import {rememberAiSeatStatus} from "../../stores/remote";
import {ConversationPauseRegistry} from "../../platform/pauseRegistry";
import {getAiSeatStatusRevision} from "../../stores/seatState";
import {parseAiSeatRuntimeStatus} from "../../platform/aiSeatDelivery";

const userStore = UserStore();
const lastServerPausedByJob = new Map<string, boolean>();

const seatStatus = ref({
    status: 'WAITING',
    label: '等待进入聊天窗口',
    globalEnabled: false,
    sessionExists: false,
    manuallyPaused: false,
    gatePaused: false,
})
let seatStatusTimer: number | null = null

const seatStatusTagType = computed(() => {
    switch (seatStatus.value.status) {
        case 'RUNNING': return 'success'
        case 'PAUSED': return 'warning'
        case 'CLOSED': return 'danger'
        default: return 'info'
    }
})

const getCurrentJobKey = () => {
    const element = document.querySelector('.friend-content.selected') as any
    const source = element?.parentElement?.__vue__?.source
    return source?.encryptJobId ? BossOption.buildJobKey(source) : ''
}

const refreshSeatStatus = async () => {
    const jobKey = getCurrentJobKey()
    if (!jobKey) {
        seatStatus.value = {...seatStatus.value, status: 'WAITING', label: '等待进入聊天窗口', sessionExists: false}
        return
    }
    const requestSeatRevision = getAiSeatStatusRevision();
    const requestPauseGeneration = ConversationPauseRegistry.beginTurn(jobKey);
    try {
        const response = await AiPower.getSessionStatus(jobKey)
        const data = response?.data?.data;
        const runtimeStatus = parseAiSeatRuntimeStatus(data);
        if (!runtimeStatus || getCurrentJobKey() !== jobKey) {
            seatStatus.value = {...seatStatus.value, status: 'ERROR', label: '状态检测失败'}
            return;
        }
        seatStatus.value = data;
        if (requestSeatRevision === getAiSeatStatusRevision()
            && Boolean(userStore.user.aiSeatStatus) !== runtimeStatus.globalEnabled) {
            rememberAiSeatStatus(runtimeStatus.globalEnabled, userStore.user);
        }

        const serverPaused = runtimeStatus.manuallyPaused || runtimeStatus.gatePaused;
        if (requestPauseGeneration === ConversationPauseRegistry.beginTurn(jobKey)) {
            if (serverPaused && !ConversationPauseRegistry.isPaused(jobKey)) {
                ConversationPauseRegistry.pause(jobKey);
            } else if (lastServerPausedByJob.get(jobKey) === true
                && !serverPaused
                && ConversationPauseRegistry.isPaused(jobKey)) {
                ConversationPauseRegistry.resume(jobKey);
            }
        }
        lastServerPausedByJob.set(jobKey, serverPaused);
    } catch (error) {
        seatStatus.value = {...seatStatus.value, status: 'ERROR', label: '状态检测失败'}
    }
}

const handlerClick = () => {
    const jobKey = getCurrentJobKey()
    if (!jobKey) {
        ElMessage({
            type: 'info',
            message: '请先进入聊天窗口'
        })
        return;
    }

    AiPower.updateAskStatus(jobKey, false).then(async _ => {
        await refreshSeatStatus()
        ElMessage({
            type: 'success',
            message: seatStatus.value.status === 'RUNNING' ? '已恢复，当前会话运行中' : `已恢复：${seatStatus.value.label}`
        })
    }).catch(() => {
        seatStatus.value = {...seatStatus.value, status: 'ERROR', label: '状态检测失败'}
        ElMessage({type: 'error', message: '恢复失败，请刷新聊天页面后重试'})
    })
}

onMounted(() => {
    void refreshSeatStatus()
    seatStatusTimer = window.setInterval(() => void refreshSeatStatus(), 5000)
})

onUnmounted(() => {
    if (seatStatusTimer) {
        window.clearInterval(seatStatusTimer)
        seatStatusTimer = null
    }
})

</script>

<style scoped>
.ai-seat-toolbar {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    margin: 8px 0;
}

.ai-seat-status {
    min-height: 30px;
    display: inline-flex;
    align-items: center;
    font-weight: 600;
}

.ai-seat-status-dot {
    display: inline-block;
    width: 9px;
    height: 9px;
    margin-right: 6px;
    border-radius: 50%;
    background: currentColor;
    box-shadow: 0 0 0 3px rgba(255, 255, 255, 0.3);
}

.status-running { animation: seat-pulse 1.6s ease-in-out infinite; }
.status-error { background: #f56c6c; }

@keyframes seat-pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.45; }
}
</style>
