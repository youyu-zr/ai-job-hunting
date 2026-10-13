<template>
    <el-dialog v-model="productStore.showProduct" :show-close="false" width="800">
        <template #header="{ close }">
            <div class="my-header">
                <el-text size="large" style="font-size: 20px" type="info">内测权限</el-text>
                <el-button type="warning" @click="close">
                    <el-icon class="el-icon--left">
                        <CircleCloseFilled/>
                    </el-icon>
                    关闭
                </el-button>
            </div>

            <div v-if="betaAccess.activationEnabled || betaAccess.permanent || betaAccess.betaActivated" class="beta-access-panel" v-loading="betaLoading">
                <div class="beta-access-heading">
                    <h3>内测权限</h3>
                    <el-tag v-if="betaAccess.permanent" type="success">长期有效</el-tag>
                    <el-tag v-else-if="betaAccess.accessActive" type="success">当前可用</el-tag>
                    <el-tag v-else-if="betaAccess.betaActivated" type="danger">已过期</el-tag>
                    <el-tag v-else-if="betaAccess.activationEnabled" type="info">待自动开通</el-tag>
                    <el-tag v-else type="info">当前未开放</el-tag>
                </div>
                <p v-if="betaAccess.permanent" class="beta-access-copy">当前账号为长期内测账号。</p>
                <p v-else-if="betaAccess.accessActive && betaAccess.periodOfValidityEndTime" class="beta-access-copy">
                    内测全功能有效至 {{ formatDate(betaAccess.periodOfValidityEndTime) }}。
                </p>
                <p v-else-if="betaAccess.accessActive" class="beta-access-copy">当前已有有效产品，可继续使用。</p>
                <p v-else-if="betaAccess.betaActivated && betaAccess.activationEnabled" class="beta-access-copy">
                    登录时会按当前开放规则自动更新内测权限；导入简历不是续期条件。
                </p>
                <p v-else-if="betaAccess.betaActivated" class="beta-access-copy">内测权限已结束，请等待后续开放安排。</p>
                <p v-else-if="betaAccess.activationEnabled" class="beta-access-copy">
                    BOSS 身份验证通过后，账号会自动创建并开通内测权限，无需导入简历；开始投递或开启 AI 坐席前，请先上传本人附件简历并点击“导入简历”。
                </p>
                <p v-else class="beta-access-copy">当前内测自动开通已结束，请等待后续开放安排。</p>
            </div>

        </template>
    </el-dialog>
</template>

<script setup lang="ts">

import {CircleCloseFilled} from "../icons";
import {inject, ref, watch} from "vue";
import {AxiosInstance} from "axios";
import logger from "../../logging";
import {ProductStore} from "../../stores";

const productStore = ProductStore()

const axios = inject('$axios') as AxiosInstance
interface BetaAccess {
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
})
const betaLoading = ref(false)

// --------------------------------------------------函数定义-------------------------------------------------------------

const formatDate = (value?: string): string => {
    if (!value) {
        return ''
    }
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', {hour12: false})
}


// --------------------------------------------------函数定义-------------------------------------------------------------


const queryBetaAccess = async () => {
    betaLoading.value = true
    try {
        const response = await axios.get('/api/beta/access')
        betaAccess.value = response.data.data
    } catch (error) {
        logger.warn('内测权限查询失败', error)
    } finally {
        betaLoading.value = false
    }
}

const openProductDialog = async () => {

    await queryBetaAccess()
}


watch(
    () => productStore.showProduct,
    (newVal) => {
        if (newVal) {
            openProductDialog()
        }
    }
);


</script>

<style scoped>
.my-header {
    display: flex;
    flex-direction: row;
    justify-content: space-between;
    gap: 16px;
}

.beta-access-panel {
    margin: 8px 0 18px;
    padding: 14px 16px;
    border: 1px solid #dcdfe6;
    border-radius: 8px;
    background: #f8fafc;
}

.beta-access-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
}

.beta-access-heading h3 {
    margin: 0;
}

.beta-access-copy {
    margin: 8px 0 0;
    color: #606266;
    line-height: 1.6;
}

</style>
