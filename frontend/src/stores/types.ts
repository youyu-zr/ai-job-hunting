import {PlatformTypeEnum} from "../platform/platform";

export type User = {
    phone: string
    email: string
    resumeId: string
    aiSeatStatus: number,
    inviteCode: string,
    bindInviteCode: string,
    preference: PreferenceConfig
    preferenceMap: Map<PlatformTypeEnum, PreferenceConfig>

}

export type PreferenceConfig = {
    /**
     * 公司名包含开关
     */
    cniE: boolean,
    /**
     * 公司名包含
     */
    cni: string[],
    /**
     * 公司名排除开关
     */
    cneE: boolean,
    /**
     * 公司名排除
     */
    cne: string[],
    /**
     * 工作名包含开关
     */
    jniE: boolean,
    /**
     * 工作名包含
     */
    jni: string[],
    /**
     * 工作名排除开关
     */
    jneE: boolean,
    /**
     * 工作名排除
     */
    jne: string[],
    /**
     * 工作名内容排除开关
     */
    jceE: boolean,
    /**
     * 工作名内容排除
     */
    jce: string[],
    /**
     * 工作名内容包含开关
     */
    jciE: boolean,
    /**
     * 工作名内容包含
     */
    jci: string[],
    /**
     * 薪资范围开关
     */
    srE: boolean,
    /**
     * 薪资范围
     */
    sr: string,
    /**
     * 公司规模范围开关
     */
    csrE: boolean,
    /**
     * 公司规模范围
     */
    csr: string,
    /**
     * 过滤猎头开关
     */
    fhE: boolean,
    /**
     * 仅投递在线boss
     */
    polE: boolean,
    /**
     * 发送自定义招呼语
     */
    cgE: boolean,
    /**
     * 自定义招呼语
     */
    cg: string,
    /**
     * ai过滤开关
     */
    afE: boolean,
    /**
     * ai过滤条件
     */
    af: string,
    /**
     * 发送自定义图片简历
     */
    cIE: boolean,
    /**
     * 自定义图片地址
     */
    cI: string,
    /** 用户的求职目标与沟通条件，仅用于相关筛选和聊天场景 */
    jobGoal?: string,
    unacceptableConditions?: string,
    clarifyConditions?: string,
    communicationPreferences?: string,
    /** NATURAL or PROACTIVE. */
    communicationStyle?: string,
    /** Default risk-pack initialization marker, mirrored from the server. */
    drpV?: string,
    /** One-time initialization marker for editable personal preference defaults. */
    pdpV?: string,
    /**
     * 高意向ai坐席停止开关
     */
    hiaE: boolean,
    /** 营销推广类消息自动回复，默认关闭。 */
    marketingReplyE?: boolean,
    /** AI 回复里允许带出手机号/微信号/邮箱，默认关闭。 */
    contactShareE?: boolean,
    /** Editable guidance for promotion types the user does not want answered. */
    promotionReplyPolicy?: string,
    /**
     * 投递间隔时间（秒）
     */
    pi: number,
    /**
     * 翻页间隔时间（秒）
     */
    npi: number,
    /** Personal location consistency guard. */
    locationGuardE?: boolean,
    resumeCity?: string,
    targetCities?: string[],
    locationMismatchThreshold?: number,
}

/**
 * 回答类型枚举
 */
export enum JobSeekerClonedAnswerTypeEnum {
    MSG_TEXT = 1,
    BOSS_OPERATION = 2,
    STOP = 3,
    AI_SERVICE_EXCEPTION = 4,

}

/**
 * boss操作类型
 */
export enum BossOperationTypeEnum {
    SEND_RESUME = 1,
}
