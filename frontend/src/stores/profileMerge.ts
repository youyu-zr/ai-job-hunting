const EDITABLE_FIELDS = [
    "phone",
    "email",
    "resumeId",
    "inviteCode",
    "bindInviteCode"
] as const;

export type UserConfigSnapshot = {
    fields: Record<string, unknown>;
    preference: string;
}

export function captureUserConfigSnapshot(user: any): UserConfigSnapshot {
    const fields: Record<string, unknown> = {};
    for (const field of EDITABLE_FIELDS) {
        fields[field] = user?.[field];
    }
    return {
        fields,
        preference: JSON.stringify(user?.preference ?? {})
    };
}

export function mergeRemoteUserConfig(
    currentUser: any,
    incomingUser: any,
    baseline: UserConfigSnapshot
): Record<string, unknown> {
    const merged = {...incomingUser};
    for (const field of EDITABLE_FIELDS) {
        if (currentUser?.[field] !== baseline.fields[field]) {
            merged[field] = currentUser[field];
        }
    }
    if (JSON.stringify(currentUser?.preference ?? {}) !== baseline.preference) {
        merged.preference = currentUser.preference;
        // 本地有没保存的改动时保留本地偏好；但导入简历时服务端补上的微信号不能因此丢掉。
        // 只在本人没动过微信号这一栏时才补：本人刚清空的不能被服务端旧值填回去。
        const remoteWechat = incomingUser?.preference?.wechatId;
        const localWechat = currentUser.preference?.wechatId;
        if (remoteWechat && !localWechat && localWechat === baselineWechat(baseline)) {
            merged.preference = {...currentUser.preference, wechatId: remoteWechat};
        }
    }
    return merged;
}

function baselineWechat(baseline: UserConfigSnapshot): unknown {
    try {
        return JSON.parse(baseline.preference)?.wechatId;
    } catch {
        return undefined;
    }
}
