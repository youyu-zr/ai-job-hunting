export type StopReplyData = {
    stopReason?: unknown;
    jobFilterDecision?: unknown;
};

const TERMINAL_STOP_REASONS = new Set([
    'SESSION_PAUSED',
    'DUPLICATE_MESSAGE',
    'HUMAN_HANDOFF',
    'DUPLICATE_REPLY',
    'JOB_GATE',
    'SILENT_SYSTEM',
]);

/**
 * A STOP response is terminal only when the server explains why. Older or
 * malformed responses remain eligible for the unread compensation scan so an
 * empty/unknown AI result cannot permanently consume an HR message.
 */
export function shouldCompleteStoppedReply(data: StopReplyData | null | undefined): boolean {
    const reason = String(data?.stopReason || '').trim().toUpperCase();
    if (TERMINAL_STOP_REASONS.has(reason)) {
        return true;
    }
    const decision = data?.jobFilterDecision as any;
    return Boolean(decision && (
        decision.filter === true
        || String(decision.recommendedAction || '').trim().toUpperCase() === 'FILTER'
        || String(decision.riskLevel || '').trim().toUpperCase() === 'HIGH'
    ));
}
