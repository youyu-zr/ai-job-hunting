import type {PreferenceConfig} from "./types";

export const PERSONAL_PREFERENCE_DEFAULTS_VERSION = "personal-preferences-20261015-v2";
const LEGACY_PERSONAL_PREFERENCE_DEFAULTS_VERSION = "personal-preferences-20261011-v1";

export const PERSONAL_PREFERENCE_DEFAULTS = {
    jobGoal: "优先结合我的简历经历、技能和岗位职责判断匹配度。未明确填写的行业、城市、薪资或工作方式不视为硬性限制；岗位信息不完整但没有明确风险或条件冲突时继续投递，后续沟通再核实。",
    unacceptableConditions: "只把我明确填写为“不接受、不能接受、必须满足”的条件作为个人硬性限制。风险判断结合岗位实际职责和行为，不因单个词过滤。",
    clarifyConditions: "职责、薪资范围与构成、地点或办公方式、工时与加班、出差、试用期和合同期限未写清时，可以继续投递，并在聊天中向 HR 询问。",
    communicationPreferences: "礼貌、自然、简洁；只依据简历和我提供的信息，不编造经历或联系方式。薪资、地点、联系方式按我明确填写的条件判断；未填写时不擅自接受或拒绝，可向 HR 询问。不要替我确认具体面试日期或时间，也不要替我接受 offer 或签署合同。",
    promotionReplyPolicy: "默认不回复借招聘进行的加群领资料、课程培训引流、拉新返佣、代理招商等推广；正常招聘抖音/短视频运营、市场、品牌和销售岗位不因岗位名称或单个词被过滤。",
} as const;

export function applyPersonalPreferenceDefaults(preference: PreferenceConfig): PreferenceConfig {
    const version = String(preference.pdpV || "").trim();
    if (version === PERSONAL_PREFERENCE_DEFAULTS_VERSION) {
        return preference;
    }
    if (version && version !== LEGACY_PERSONAL_PREFERENCE_DEFAULTS_VERSION) {
        return preference;
    }
    const initialized = version === LEGACY_PERSONAL_PREFERENCE_DEFAULTS_VERSION;

    for (const [key, value] of Object.entries(PERSONAL_PREFERENCE_DEFAULTS)) {
        const field = key as keyof typeof PERSONAL_PREFERENCE_DEFAULTS;
        const current = preference[field];
        const oldDefault = LEGACY_PERSONAL_PREFERENCE_DEFAULTS[field];
        const newlyIntroducedField = field === "promotionReplyPolicy" && current == null;
        if (current === oldDefault || newlyIntroducedField || (!initialized && !String(current || "").trim())) {
            preference[field] = value;
        }
    }
    preference.pdpV = PERSONAL_PREFERENCE_DEFAULTS_VERSION;
    return preference;
}

const LEGACY_PERSONAL_PREFERENCE_DEFAULTS = {
    jobGoal: "优先结合我的简历经历、技能和当前岗位职责判断匹配度；不要假设我未说明的行业、城市、薪资或岗位偏好。",
    unacceptableConditions: "除系统识别的明确风险外，暂未设置额外的个人硬性限制。",
    clarifyConditions: "薪资构成、实际工作内容、办公地点或方式、工时与加班、出差或驻场、试用期等重要信息未写清时，优先提醒我向 HR 确认。",
    communicationPreferences: "保持礼貌、自然、简洁；只依据我的简历和已提供的信息回答，不编造经历；涉及电话或微信、面试安排、薪资、地点、到岗和合同承诺时，先由我本人确认。",
    promotionReplyPolicy: null,
} as const;
