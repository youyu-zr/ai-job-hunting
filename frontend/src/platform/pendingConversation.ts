export type PendingConversationInput = {
    unread: boolean;
    preview: string;
    lastMessageSenderId: number | null;
    currentUserId: number;
    selected: boolean;
    latestSender: 'boss' | 'candidate' | 'unknown';
    latestMessageObserved?: boolean;
    expectedSenderId?: number;
};

export function isPendingConversationIdentityVerified(
    targetBossId: number,
    activeBossId: number,
    latestSenderId: number | null,
): boolean {
    return Number.isSafeInteger(targetBossId)
        && targetBossId > 0
        && activeBossId === targetBossId
        && latestSenderId === targetBossId;
}

/**
 * Decide whether reconnect scanning may treat the visible contact as an
 * unanswered HR message. A known candidate-authored last message wins over an
 * unread badge because BOSS can leave stale badges after another device sends.
 */
export function shouldTreatPendingConversationAsReplyable(input: PendingConversationInput): boolean {
    const preview = String(input.preview || '').trim();
    if (!preview) {
        return false;
    }
    if (input.selected && input.latestMessageObserved) {
        return input.latestSender === 'boss';
    }
    if (!input.selected
        && input.expectedSenderId !== undefined
        && input.lastMessageSenderId !== input.expectedSenderId) {
        return false;
    }
    if (input.lastMessageSenderId !== null
        && input.currentUserId > 0) {
        return input.lastMessageSenderId !== input.currentUserId;
    }
    if (input.selected) {
        return input.latestSender === 'boss';
    }
    if (input.unread) {
        return input.lastMessageSenderId !== null
            && input.currentUserId > 0
            && input.lastMessageSenderId !== input.currentUserId;
    }
    return false;
}
