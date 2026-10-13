<template>
    <div class="container">
        <el-card class="box-card">
            <template #header>
                <div class="card-header">
                    <h3>邀请码</h3>
                    <div style="display: flex">
                        <span style="margin-right: 15px; font-size: 14px; color: #909399">
                            已邀请人数: {{ activeTrialsCount }}
                        </span>
                    </div>
                </div>
            </template>

            <el-row>
                <el-col :span="12">
                    <div class="grid-content ep-bg-purple">
                        <el-text class="info-item">您的邀请码: {{ userStore.user.inviteCode }}</el-text>
                        <el-text v-if="betaAccess.permanent" class="info-item" type="success">内测权限：长期有效</el-text>
                        <el-text v-else-if="betaAccess.accessActive && betaAccess.periodOfValidityEndTime" class="info-item" type="success">
                            内测有效至：{{ formatDate(betaAccess.periodOfValidityEndTime) }}
                        </el-text>
                        <el-text v-else-if="betaAccess.betaActivated" class="info-item" type="danger">内测权限：已过期</el-text>
                        <el-text v-else-if="betaAccess.activationEnabled" class="info-item" type="info">内测权限：BOSS 登录后自动开通</el-text>
                        <el-text v-else class="info-item" type="info">内测权限：当前未开放</el-text>

                        <el-form :inline="true" class="bind-form">
                            <el-form-item label="绑定邀请码">
                                <el-input
                                    v-model="bindInviteCode"
                                    placeholder="请输入邀请码"
                                    :clearable="bindInviteCodeStatus"
                                    :disabled="bindInviteCodeStatus"
                                />
                            </el-form-item>
                            <el-form-item>
                                <el-button type="primary" @click="bindInvite" v-if="!bindInviteCodeStatus">
                                    立即绑定
                                </el-button>
                            </el-form-item>
                        </el-form>
                    </div>
                </el-col>
            </el-row>
        </el-card>

        <el-card class="box-card">
            <template #header>
                <div class="card-header">
                    <h3>邀请列表</h3>
                </div>
            </template>

            <el-row>
                <el-col :span="24">
                    <div class="grid-content ep-bg-purple-light">
                        <el-table :data="inviteList" style="width: 100%;">
                            <el-table-column prop="beInviteeUsername" label="用户名" width="120"/>
                            <el-table-column label="状态" width="120">
                                <template #default="{ row }">
                                    <el-tag :type="row.status === 1 ? 'success' : 'info'">
                                        {{ row.status === 1 ? '正常' : '归档' }}
                                    </el-tag>
                                </template>
                            </el-table-column>
                            <el-table-column prop="createdDate" label="绑定时间" width="160"/>
                        </el-table>
                    </div>
                </el-col>
            </el-row>
        </el-card>
    </div>
</template>

<script setup lang="ts">
import {AxiosInstance} from "axios";
import {inject, onMounted, Ref, ref} from "vue";
import {Tools} from "../../platform/utils";
import logger from '../../logging';
import {UserStore} from "../../stores";
import {ElNotification} from "element-plus";

const axios = inject('$axios') as AxiosInstance;

const userStore = UserStore();

let bindInviteCode = ref(userStore.user.bindInviteCode);
let bindInviteCodeStatus = ref(!!userStore.user.bindInviteCode);

const inviteList: Ref<any[]> = ref([]);

const activeTrialsCount = ref(0);

type BetaAccess = {
    permanent: boolean
    activationEnabled: boolean
    accessActive: boolean
    betaActivated: boolean
    periodOfValidityEndTime?: string
}

const betaAccess = ref<BetaAccess>({
    permanent: false,
    activationEnabled: false,
    accessActive: false,
    betaActivated: false,
});

onMounted(async () => {
    await Promise.all([fetchInviteList(), fetchBetaAccess()]);
});

const fetchInviteList = async () => {
    try {
        const response = await axios.post('/api/user/invites/list');
        inviteList.value = response.data.data;
        activeTrialsCount.value = inviteList.value.filter(trial => trial.status === 1).length;
    } catch (error) {
        logger.error('Failed to fetch invite list', error);
    }
};

const fetchBetaAccess = async () => {
    try {
        const response = await axios.get('/api/beta/access');
        if (response.data.code === 200) {
            betaAccess.value = response.data.data;
        }
    } catch (error) {
        logger.warn('Failed to fetch beta access status', error);
    }
};

const formatDate = (value?: string): string => {
    if (!value) {
        return '';
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', {hour12: false});
};

const bindInvite = async () => {
    try {
        let param = `?inviteCode=${bindInviteCode.value}&name=${Tools.window._PAGE.name}`
        await axios.post('/api/user/invites/bind/code' + param);
        userStore.user.bindInviteCode = bindInviteCode.value;
        ElNotification({
            message: "绑定邀请码成功",
            type: 'success',
            duration: 2000
        });
        bindInviteCodeStatus.value = true;
        // 不刷新用户邀请列表，因为当前用户是被邀请者
    } catch (error) {
        logger.error('Failed to bind invite code', error);
        ElNotification({
            message: error as string,
            type: 'error',
            duration: 2000
        });
    }
};

</script>

<style scoped>
.container {
    max-width: 1200px;
    margin: 0 auto;
    padding: 0;
}

.box-card {
    margin-bottom: 20px;
}

.card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.bind-form {
    margin-top: 20px;
}

.info-item {
    font-size: 16px;
    margin-bottom: 10px;
    display: block;
}

/* 响应式布局 */
@media (max-width: 768px) {
    .container {
        padding: 10px;
    }

    .el-form--inline .el-form-item {
        margin-right: 0;
        margin-bottom: 10px;
    }
}
</style>
