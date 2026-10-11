import type {PreferenceConfig} from "./types";

export const PERSONAL_PREFERENCE_DEFAULTS_VERSION = "personal-preferences-20261011-v1";

export const PERSONAL_PREFERENCE_DEFAULTS = {
    jobGoal: "优先结合我的简历经历、技能和当前岗位职责判断匹配度；不要假设我未说明的行业、城市、薪资或岗位偏好。",
    unacceptableConditions: "除系统识别的明确风险外，暂未设置额外的个人硬性限制。",
    clarifyConditions: "薪资构成、实际工作内容、办公地点或方式、工时与加班、出差或驻场、试用期等重要信息未写清时，优先提醒我向 HR 确认。",
    communicationPreferences: "保持礼貌、自然、简洁；只依据我的简历和已提供的信息回答，不编造经历；涉及电话或微信、面试安排、薪资、地点、到岗和合同承诺时，先由我本人确认。",
} as const;

export function applyPersonalPreferenceDefaults(preference: PreferenceConfig): PreferenceConfig {
    if (preference.pdpV) {
        return preference;
    }

    for (const [key, value] of Object.entries(PERSONAL_PREFERENCE_DEFAULTS)) {
        const field = key as keyof typeof PERSONAL_PREFERENCE_DEFAULTS;
        if (!String(preference[field] || "").trim()) {
            preference[field] = value;
        }
    }
    preference.pdpV = PERSONAL_PREFERENCE_DEFAULTS_VERSION;
    return preference;
}
