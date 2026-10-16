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
    }
    return merged;
}
