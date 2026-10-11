export type AiFilterDecision = {
    filter?: boolean
    recommendedAction?: string
    riskLevel?: string
    workMode?: string
    workModeEvidence?: string[]
    reason?: string
    jobScore?: number
    companyScore?: number
    matchScore?: number
    goodSignals?: string[]
    riskSignals?: string[]
    prepSkills?: string[]
}

/**
 * The delivery gate only blocks an explicit semantic rejection or a high-risk
 * result. HUMAN_REVIEW is not a rejection: it means the job is ambiguous and
 * should be clarified with HR after delivery. This keeps delivery and chat on
 * the same boundary instead of silently dropping otherwise usable jobs.
 */
export function shouldBlockAfterAiFilter(result: AiFilterDecision | null | undefined): boolean {
    if (!result) {
        return true
    }
    if (result.filter === true) {
        return true
    }
    const action = String(result.recommendedAction || '').trim().toUpperCase()
    const risk = String(result.riskLevel || '').trim().toUpperCase()
    if (risk === 'HIGH') {
        return true
    }
    return action === 'FILTER'
}

/**
 * Client-side screening is an optimization, not the authority for chat.
 * Missing or temporarily unavailable results must reach the server, which
 * has the authenticated resume/preferences and its own screening fallback.
 */
export function shouldBlockConversationPreflight(result: AiFilterDecision | null | undefined): boolean {
    if (!result || typeof result !== 'object') {
        return false;
    }
    if (result.filter === true) {
        return true;
    }
    const action = String(result.recommendedAction || '').trim().toUpperCase();
    const risk = String(result.riskLevel || '').trim().toUpperCase();
    // HUMAN_REVIEW means the job deserves attention, not that an already
    // opened conversation must be swallowed. Only an explicit filter or high
    // risk blocks the AI chat preflight; the server still owns final decisions.
    return risk === 'HIGH' || action === 'FILTER';
}

export function aiFilterBlockReason(result: AiFilterDecision | null | undefined): string {
    if (!result) {
        return 'AI过滤无结果，停止投递'
    }
    if (result.reason) {
        return result.reason
    }
    const action = String(result.recommendedAction || '').trim().toUpperCase()
    const risk = String(result.riskLevel || '').trim().toUpperCase()
    return `AI建议${action || '未知动作'}，风险等级${risk || '未知'}`
}

export function aiFilterAction(result: AiFilterDecision | null | undefined): string {
    if (!result) {
        return 'HUMAN_REVIEW'
    }
    const action = String(result.recommendedAction || '').trim().toUpperCase()
    if (result.filter === true || result.riskLevel?.toUpperCase?.() === 'HIGH') {
        return 'FILTER'
    }
    return action || 'HUMAN_REVIEW'
}

export function compactAiFilterReason(result: AiFilterDecision | null | undefined): string {
    if (!result) {
        return '没有拿到AI岗位分析结果'
    }
    const reason = String(result.reason || '').replace(/\s+/g, ' ').trim()
    const risks = Array.isArray(result.riskSignals)
        ? result.riskSignals.filter(Boolean).slice(0, 2).join('、')
        : ''
    const missing = !reason && !risks ? '岗位信息不足，需人工确认' : [reason, risks].filter(Boolean).join('；')
    return missing
}

/** Keeps the complete AI explanation for the final delivery record. */
export function formatAiFilterReasonForDelivery(result: AiFilterDecision | null | undefined): string {
    if (!result) {
        return '通过当前筛选条件'
    }
    const normalize = (value: unknown): string => String(value ?? '')
        .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim()
    const reason = normalize(result.reason)
    const risks = Array.isArray(result.riskSignals)
        ? result.riskSignals.filter(Boolean).map(normalize).filter(Boolean)
        : []
    const goodSignals = Array.isArray(result.goodSignals)
        ? result.goodSignals.filter(Boolean).map(normalize).filter(Boolean)
        : []
    const sections = [
        reason,
        goodSignals.length ? `正向信息：${goodSignals.join('、')}` : '',
        risks.length ? `风险信息：${risks.join('、')}` : '',
    ].filter(Boolean)
    return sections.length ? sections.join('；') : '通过当前筛选条件'
}
