<template>
    <el-form
        ref="ruleFormRef"
        :model="userStore.user"
        :rules="rules as FormRules<RuleForm>"
        label-position="right"
        label-width="auto"
        class="form-preference"
        size="large"
        status-icon>

        <div>
            <div v-if="Tools.window.location.href.includes('job-recommend')">
                <el-text class="mx-1 top-title" type="danger">!!!请前往顶部【搜索】按钮所在页面保存偏好设置!!!</el-text>
                <br/>
                <br/>
            </div>
            <el-text class="mx-1 top-title" type="warning">账号信息</el-text>
            <div style="display: flex;margin-top: 10px">
                <el-form-item label="手机号（可选）" prop="phone" style="margin-left: -6px;">
                    <el-input v-model="userStore.user.phone"/>
                </el-form-item>

                <el-form-item label="通知邮箱（可选，不填不发邮件）" prop="email">
                    <el-input v-model="userStore.user.email"/>
                </el-form-item>
            </div>

            <el-text class="mx-1 top-title" type="warning">求职要求</el-text>
            <el-text type="info" size="small" class="section-hint">
                已预填通用建议，保存后会参与岗位匹配和相关沟通。你可以直接使用，也可以按自己的情况删改或补充；清空并保存后，该项会保持为空，不会自动填回。
            </el-text>
            <el-form-item label="你想找什么样的工作？" style="margin-top: 10px;">
                <el-input v-model="userStore.user.preference.jobGoal" type="textarea" :rows="2" :maxlength="1200"
                          show-word-limit placeholder="补充你的目标岗位、经验阶段、城市或工作方式等个人要求。" />
            </el-form-item>
            <el-form-item label="哪些条件明确不能接受？">
                <el-input v-model="userStore.user.preference.unacceptableConditions" type="textarea" :rows="2" :maxlength="1200"
                          show-word-limit placeholder="补充你个人明确不能接受的条件；没有的话可以清空。" />
            </el-form-item>
            <el-form-item label="哪些情况需要先了解清楚？">
                <el-input v-model="userStore.user.preference.clarifyConditions" type="textarea" :rows="2" :maxlength="1200"
                          show-word-limit placeholder="补充你希望先向 HR 了解的个人问题。" />
            </el-form-item>
            <el-form-item label="沟通与面试偏好">
                <el-input v-model="userStore.user.preference.communicationPreferences" type="textarea" :rows="3" :maxlength="1600"
                          show-word-limit placeholder="补充你的沟通时间、方式或面试安排偏好。" />
            </el-form-item>

            <el-text class="mx-1 top-title" type="warning">投递设置</el-text>
            <el-form-item label="地区一致性保护" prop="locationGuard" style="margin-top: 10px;">
                <template #label>
                    <el-checkbox v-model="userStore.user.preference.locationGuardE" label="" size="large"/>
                    地区一致性保护
                </template>
                <div style="display: flex; flex-wrap: wrap; gap: 8px; align-items: center;">
                    <el-input v-model="userStore.user.preference.resumeCity" placeholder="简历/现居城市，如商丘" style="width: 180px"/>
                    <el-select v-model="userStore.user.preference.targetCities" multiple filterable allow-create default-first-option
                               placeholder="本轮目标城市" style="width: 240px">
                        <el-option v-for="city in ['上海', '商丘', '北京', '杭州', '深圳']" :key="city" :label="city" :value="city"/>
                    </el-select>
                    <span>连续异地</span>
                    <el-input-number v-model="userStore.user.preference.locationMismatchThreshold" :min="1" :max="5" size="small"/>
                    <span>次后暂停</span>
                </div>
                <el-text type="info" size="small">只按当前个人配置校验搜索城市和职位地点，不自动修改简历城市；第一次异地提醒，达到次数暂停本轮投递。</el-text>
            </el-form-item>
            <div style="display: flex;margin-top: 10px">
                <el-form-item prop="companyInclude" style="margin-left: -40px;">
                    <template #label>
                        <el-checkbox v-model="userStore.user.preference.cniE" label="" size="large"/>
                        公司名包含
                    </template>
                    <el-select v-model="userStore.user.preference.cni"
                               multiple
                               filterable
                               remote
                               allow-create
                               default-first-option
                               :reserve-keyword="false"
                               placeholder="公司名包含"
                               style="width: 240px">
                        <el-option v-for="(item,inx) in ['请输入公司名']"
                                   :key="inx"
                                   :label="item"
                                   :value="item"/>
                    </el-select>
                </el-form-item>

                <el-form-item label="公司名排除" prop="companyExclude">
                    <template #label>
                        <el-checkbox v-model="userStore.user.preference.cneE" label="" size="large"/>
                        公司名排除&nbsp;&nbsp;&nbsp;
                    </template>
                    <el-select v-model="userStore.user.preference.cne"
                               multiple
                               filterable
                               remote
                               allow-create
                               default-first-option
                               :reserve-keyword="false"
                               placeholder="公司名排除"
                               style="width: 240px">
                        <el-option v-for="(item,inx) in ['请输入公司名']"
                                   :key="inx"
                                   :label="item"
                                   :value="item"/>
                    </el-select>
                </el-form-item>
            </div>

            <div style="display: flex">
                <el-form-item label="工作名包含" style="margin-left: -40px;" prop="jobNameInclude">
                    <template #label>
                        <el-checkbox v-model="userStore.user.preference.jniE" label="" size="large"/>
                        工作名包含
                    </template>
                    <el-select v-model="userStore.user.preference.jni"
                               multiple
                               filterable
                               remote
                               allow-create
                               default-first-option
                               :reserve-keyword="false"
                               placeholder="工作名包含"
                               style="width: 240px">
                        <el-option v-for="(item,inx) in ['请输入工作名']"
                                   :key="inx"
                                   :label="item"
                                   :value="item"/>
                    </el-select>
                </el-form-item>

                <el-form-item label="工作名排除" prop="jobContentExclude">
                    <template #label>
                        <el-checkbox v-model="userStore.user.preference.jneE" label="" size="large"/>
                        工作名排除&nbsp;&nbsp;&nbsp;
                    </template>
                    <el-select v-model="userStore.user.preference.jne"
                               multiple
                               filterable
                               remote
                               allow-create
                               default-first-option
                               :reserve-keyword="false"
                               placeholder="工作名排除"
                               style="width: 240px">
                        <el-option v-for="(item,inx) in ['请输入岗位名称']"
                                   :key="inx"
                                   :label="item"
                                   :value="item"/>
                    </el-select>
                </el-form-item>
            </div>

            <div style="display: flex">
                <el-form-item label="工作内容包含" style="margin-left: -40px;" prop="jobContentInclude">
                    <template #label>
                        <el-checkbox v-model="userStore.user.preference.jciE" label="" size="large"/>
                        &nbsp;&nbsp;&nbsp;&nbsp;内容包含
                    </template>
                    <el-select v-model="userStore.user.preference.jci"
                               multiple
                               filterable
                               remote
                               allow-create
                               default-first-option
                               :reserve-keyword="false"
                               placeholder="工作内容包含"
                               style="width: 240px">
                        <el-option v-for="(item,inx) in ['请输入工作内容']"
                                   :key="inx"
                                   :label="item"
                                   :value="item"/>
                    </el-select>
                </el-form-item>

                <el-form-item label="工作内容排除" prop="jobContentExclude">
                    <template #label>
                        <el-checkbox v-model="userStore.user.preference.jceE" label="" size="large"/>
                        工作内容排除
                    </template>
                    <el-select v-model="userStore.user.preference.jce"
                               multiple
                               filterable
                               remote
                               allow-create
                               default-first-option
                               :reserve-keyword="false"
                               placeholder="工作内容排除"
                               style="width: 240px">
                        <el-option v-for="(item,inx) in ['请输入工作内容字符串']"
                                   :key="inx"
                                   :label="item"
                                   :value="item"/>
                    </el-select>
                </el-form-item>
            </div>

            <!--            <div class="form-bottom">-->
            <el-form-item label="薪资范围" prop="salaryRange">
                <div style="display: flex; flex-wrap: wrap; align-items: center; gap: 8px">
                    <el-checkbox v-model="userStore.user.preference.srE" label="" size="large"/>
                    <el-input class="input-opt"
                              v-model="userStore.user.preference.sr"
                              style="width: 324px"
                              placeholder="例如 8-12、12以上或20以下"/>
                </div>
                <el-text type="info" size="small">只填写数字范围：8-12 表示区间，12以上表示最低要求，20以下表示最高要求；留空或关闭表示不限制。AI语义筛选开启时会结合岗位完整信息综合判断，关闭时才按范围严格跳过。</el-text>
            </el-form-item>

            <div style="display: flex">
                <el-form-item label="公司规模范围" prop="jobContentExclude" style="margin-left: 0;">
                    <template #label>
                        <el-checkbox v-model="userStore.user.preference.csrE" label="" size="large"/>
                        公司规模范围
                    </template>
                    <el-input v-model="userStore.user.preference.csr" placeholder="公司规模范围 例:10-5000"
                              style="width: 242px"/>
                </el-form-item>
            </div>

            <el-form-item label="AI过滤(语义匹配)" prop="aiFilter">
                <template #label>
                    <el-checkbox v-model="userStore.user.preference.afE" label="" size="large"/>
                    <el-tooltip effect="dark" raw-content content="
    默认开启：系统会结合简历、求职要求和完整职位信息筛选岗位。<br/>明确风险由系统底层规则识别；信息不足但无明确风险时，先聊天确认。个人条件请填写在上方的求职要求中。
    " placement="bottom">
                    AI 过滤(语义匹配)
                    </el-tooltip>
                </template>
                <el-text type="info" size="small">默认开启。系统会结合岗位、公司、简历和你的个人要求综合判断；不按单个岗位词直接过滤。只有需要关闭或调整时才操作这里。</el-text>
            </el-form-item>

            <el-form-item label="招呼语补充要求（个人可选）" prop="jobContentExclude">
                <template #label>
                    招呼语补充要求（个人可选）
                </template>
                <el-input
                    type="textarea"
                    v-model="userStore.user.preference.cg"
                    placeholder="例如：优先突出资料整理或 AI 工具使用，不要写成广告文案。留空也可以。"/>
                <el-text type="info" size="small">底层生成规则由系统统一维护；这里不需要填写完整 Prompt，只填写个人关注点或表达偏好。投递时会结合岗位、公司、简历和求职要求生成，生成失败不会发送通用文案。</el-text>
            </el-form-item>

            <el-form-item label="发送图片简历" prop="jobContentExclude" class="form-item-upload" style="margin-left: 0;">
                <template #label>
                    <el-checkbox v-model="userStore.user.preference.cIE" label="" size="large"/>
                    发送图片简历&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
                </template>

                <el-upload
                    action="https://www.zhipin.com/wapi/zpupload/image/uploadSingle"
                    :before-upload="beforeUpload"
                    :on-success="handleUploadSuccess"
                    :on-error="handleUploadError"
                    :show-file-list="false"
                    :data="uploadData"
                    :headers='{"Zp_token": Tools.getCookieValue("bst")}'>
                    <el-button size="small" type="primary">选择图片简历</el-button>
                </el-upload>
                <el-tag v-if="userStore.user.preference.cI" type="success" size="small" style="margin-left: 5px;">已上传</el-tag>
            </el-form-item>

            <div style="display: flex;margin-bottom: 10px;">
                <el-checkbox v-model="userStore.user.preference.fhE" label="" size="large">过滤猎头</el-checkbox>
                <el-checkbox v-model="userStore.user.preference.polE" label="" size="large">仅投递boss在线岗位
                </el-checkbox>
                &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
                <p class="time-interval">投递间隔</p>
                <el-input-number v-model="userStore.user.preference.pi" :min="3" :max="60"
                                 size="small"></el-input-number>
                <p class="time-interval">秒</p>
                &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
                <p class="time-interval">翻页间隔</p>
                <el-input-number v-model="userStore.user.preference.npi" :min="6" :max="60"
                                 size="small"></el-input-number>
                <p class="time-interval">秒</p>
            </div>

            <el-text class="mx-1 top-title" type="warning">交互设置</el-text>

            <el-form-item label="AI沟通风格" style="margin-top: 10px;">
                <el-radio-group v-model="userStore.user.preference.communicationStyle">
                    <el-radio-button label="NATURAL">自然稳妥（默认）</el-radio-button>
                    <el-radio-button label="PROACTIVE">主动了解</el-radio-button>
                </el-radio-group>
            </el-form-item>
            <el-text type="info" size="small" class="section-hint">
                两种风格只改变沟通主动程度，不会改变风险识别、简历事实边界或人工确认规则。
            </el-text>

            <el-form-item>
                <el-checkbox style="padding-top: 6px" v-model="userStore.user.preference.hiaE" label="" size="large">高意向时暂停AI坐席</el-checkbox>
                <el-tooltip effect="dark" placement="top" content="开启后，AI完成普通回复后会复核岗位匹配和HR的明确推进；高置信度高意向或复核不可用时，暂停当前岗位会话。联系方式交换、明确面试安排、地点接受、薪资/到岗承诺等独立硬规则不受此开关影响；明显收费、拉人头等岗位风险由岗位风险规则处理。">
                    <el-text type="primary" style="cursor: help;">查看规则</el-text>
                </el-tooltip>
            </el-form-item>
            <el-text type="info" size="small" class="section-hint">
                联系方式交换、明确面试安排、地点接受及薪资、到岗、录用或合同承诺，会独立暂停当前岗位会话；普通岗位交流和薪资/地点信息介绍不会仅因提到这些词而暂停。
            </el-text>
            <el-form-item>
                <el-checkbox
                    style="padding-top: 6px"
                    v-model="userStore.user.preference.marketingReplyE"
                    label=""
                    size="large"
                >营销推广类消息自动回复</el-checkbox>
                <el-tooltip
                    effect="dark"
                    placement="top"
                    content="默认关闭。扫码进群、注册充值、课程引流、返佣拉新等明显推广消息不会自动回复；打开后只允许普通推广消息进入AI，收费、刷单、传销和账号交易等硬风险仍不会自动回复。"
                >
                    <el-text type="primary" style="cursor: help;">查看规则</el-text>
                </el-tooltip>
            </el-form-item>
            <el-text type="info" size="small" class="section-hint">
                默认不回复营销引流消息；正常岗位职责里出现“推广”“市场”“销售”等词，不会单独触发静默。
            </el-text>

            <el-form-item>
                <el-button type="primary" @click="submitForm(ruleFormRef)">保存偏好设置</el-button>
                <el-button @click="resetForm(ruleFormRef)">恢复默认偏好</el-button>
            </el-form-item>
        </div>
    </el-form>
</template>

<script lang="ts" setup>
import {inject, reactive, ref} from 'vue'
import {FormInstance, FormRules, ElNotification} from 'element-plus';
import {ElMessage} from "../../utils/tools";
import {UserStore} from '../../stores'
import {AxiosInstance} from "axios";
import {PreferenceConfig} from "../../stores/types";
import {loginInterceptor} from "../../utils/tools";
import {Tools} from "../../platform/utils";
import {AbsPlatform} from "../../platform/platform";

import {ServerStore} from "../../stores/server";
import {TampermonkeyApi} from "../../platform/utils";
import {applyPersonalPreferenceDefaults} from "../../stores/preferenceDefaults";
import {userSnapshotStorageKey} from "../../stores/userSnapshotKey";

const axios = inject('$axios') as AxiosInstance
const platform = inject('$platform') as AbsPlatform;
const userStore = UserStore();
const serverStore = ServerStore();

function persistLocalUserConfig(): void {
    const bossAccountId = String(Tools.window?._PAGE?.uid ?? '').trim();
    const mirrorKey = serverStore.getMirrorKey('user_config', bossAccountId);
    const snapshotKey = userSnapshotStorageKey(bossAccountId, serverStore.baseUrl);
    if (!mirrorKey || !snapshotKey) {
        return;
    }
    TampermonkeyApi.GmSetValue(mirrorKey, userStore.user);
    localStorage.setItem(snapshotKey, JSON.stringify(userStore.user));
}

interface RuleForm {
    phone: string,
    email: string,
    companyIncludeEnable: boolean,
    companyInclude: string[],

    companyExcludeEnable: boolean,
    companyExclude: string[],

    jobNameIncludeEnable: boolean,
    jobNameInclude: string[],

    jobContentExcludeEnable: boolean,
    jobContentExclude: string[],

    salaryRangeEnable: boolean,
    salaryType: string,
    salaryRange: string,

    companyScaleRangeEnable: boolean,
    companyScaleRange: string,

    sendCustomizeGreetEnable: boolean,
    customizeGreet: string,
}

const ruleFormRef = ref<FormInstance>()


const validateEmail = (rule: any, value: string, callback: (error?: Error) => void) => {
    if (value == null || value.trim() === '') {
        callback();
    } else if (!/^[\w-]+(\.[\w-]+)*@[\w-]+(\.[\w-]+)+$/.test(value)) {
        callback(new Error('请输入正确的邮箱'));
    } else {
        callback();
    }
};

const validateSalaryRange = (rule: any, value: string, callback: (error?: Error) => void) => {
    if (!userStore.user.preference.srE || value == null || value.trim() === '') {
        callback();
        return;
    }
    if (!Tools.isSalaryRangeInputValid(value)) {
        callback(new Error('请填写如 8-12、12以上或20以下的薪资范围'));
        return;
    }
    callback();
};

const rules = reactive<FormRules<RuleForm>>({
    email: [{
        validator: validateEmail,
        trigger: 'blur'
    }],
    salaryRange: [{
        validator: validateSalaryRange,
        trigger: 'blur'
    }],
})


const submitForm = async (formEl: FormInstance | undefined) => {
    if (!loginInterceptor()) {
        return;
    }
    if (!formEl) return
    let valid = await formEl.validate((valid, fields) => {
        return valid;
    })

    if (!valid) {
        return;
    }

    const phone = String(userStore.user.phone || '').trim();
    const email = String(userStore.user.email || '').trim();
    userStore.user.phone = phone;
    userStore.user.email = email;
    persistLocalUserConfig();
    await axios.post("/api/user/save/preference", {
        phone,
        email,
        preference: userStore.user.preference
    })
        .then(resp => {
            ElMessage({
                message: "偏好设置已同步到服务器",
                type: 'success',
                duration: 2000
            })
        })
        .catch(err => {
            ElNotification({
                title: '保存至本地',
                message: '由于服务器离线，配置仅在本地生效。连接恢复后请重新同步。',
                type: 'warning',
                duration: 4000
            });
        })
}

const resetForm = async (formEl: FormInstance | undefined) => {
    if (!formEl) return
    userStore.user.phone = "";
    userStore.user.email = "";
    userStore.user.preference = {} as PreferenceConfig;
    preferenceDefaultValueHandler();
    persistLocalUserConfig();
    formEl.clearValidate();
    try {
        await axios.post("/api/user/save/preference", {
            phone: "",
            email: "",
            preference: {}
        });
        ElMessage({
            message: "默认偏好已恢复并同步到服务器",
            type: 'success',
            duration: 2000
        });
    } catch (_error) {
        ElNotification({
            title: '清除未同步',
            message: '本地偏好已清除，服务器连接恢复后请再次保存',
            type: 'warning',
            duration: 3000
        });
    }
}

// 图片简历功能
const firstFile = ref<File | null>(null);
let jobDetail: any = platform.getFistJobDetail()
const uploadData = reactive({
    securityId: jobDetail?.securityId || '',
    // securityId: BossOption.bossUserInfoMap?.values()?.next()?.value.securityId,
    source: 'chat_file',
});
const beforeUpload = (file: File) => {
    firstFile.value = file;
    const currentJob = platform.getFistJobDetail() as any;
    uploadData.securityId = currentJob?.securityId || jobDetail?.securityId || '';
    return true;
};

const handleUploadSuccess = async (response: any) => {
    const originImageUrl = String(response?.zpData?.url || '').trim();
    const tinyImageUrl = String(response?.zpData?.tinyUrl || '').trim();
    if (!originImageUrl || !tinyImageUrl) {
        ElNotification({
            title: '图片简历上传失败',
            message: 'BOSS 未返回完整的原图和缩略图地址，请重新上传',
            type: 'error',
            duration: 3000
        });
        return;
    }
    userStore.user.preference.cI = originImageUrl + "===" + tinyImageUrl;
    ElMessage({
        message: "图片简历上传成功；点击下方保存偏好设置可持久保存",
        type: 'success',
        duration: 3000
    })
};

const handleUploadError = () => {
    ElNotification({
        title: '图片简历上传失败',
        message: '请确认 BOSS 仍处于登录状态后重试',
        type: 'error',
        duration: 3000
    });
};

/**
 * 偏好设置默认值处理
 */
const preferenceDefaultValueHandler = () => {
    const preference = userStore.user.preference;
    preference.afE = preference.afE ?? true;
    preference.jceE = preference.jceE ?? false;
    preference.jce = Array.isArray(preference.jce) ? preference.jce : [];
    preference.pi = preference.pi || 3;
    preference.npi = preference.npi || 6;
    preference.locationGuardE = preference.locationGuardE ?? false;
    preference.resumeCity = preference.resumeCity || '';
    preference.targetCities = Array.isArray(preference.targetCities) ? preference.targetCities : [];
    preference.locationMismatchThreshold = preference.locationMismatchThreshold || 2;
    preference.marketingReplyE = preference.marketingReplyE ?? false;
    if (!userStore.user.preference.communicationStyle) {
        userStore.user.preference.communicationStyle = 'NATURAL';
    }
    preference.drpV = preference.drpV || 'risk-pack-20260829-v2';
    applyPersonalPreferenceDefaults(preference);
}

preferenceDefaultValueHandler()

</script>

<style scoped>

.input-opt > :first-child {
    width: 100px;
}

.form-item-upload > :first-child {
    margin-left: 0;
}

.el-input-number--small {
    line-height: 22px;
    width: 80px;
}

.time-interval{
    margin-top: 10px;
    margin-right: 1px;
    margin-left: 1px;
}


</style>
