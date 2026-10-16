let aiSeatStatusRevision = 0

export function markAiSeatStatusMutation(): number {
    aiSeatStatusRevision += 1
    return aiSeatStatusRevision
}

export function getAiSeatStatusRevision(): number {
    return aiSeatStatusRevision
}

export function shouldApplyAiSeatRollback(operationRevision: number): boolean {
    return operationRevision === aiSeatStatusRevision
}

export function normalizeAiSeatStatus(value: unknown): boolean | undefined {
    if (value === undefined || value === null) {
        return undefined
    }
    if (typeof value === 'boolean') {
        return value
    }
    if (typeof value === 'number') {
        return value === 1
    }
    if (typeof value === 'string') {
        const normalized = value.trim().toLowerCase()
        if (['1', 'true', 'open', 'on'].includes(normalized)) {
            return true
        }
        if (['0', 'false', 'close', 'off'].includes(normalized)) {
            return false
        }
    }
    return Boolean(value)
}

export function didAiSeatStatusChange(nextValue: unknown, currentValue: unknown): boolean {
    const nextStatus = normalizeAiSeatStatus(nextValue);
    const currentStatus = normalizeAiSeatStatus(currentValue);
    return nextStatus !== undefined
        && currentStatus !== undefined
        && nextStatus !== currentStatus;
}

export function serializeAiSeatStatus(value: unknown): 1 | 0 {
    return normalizeAiSeatStatus(value) ? 1 : 0
}

export function resolveRemoteAiSeatStatus(
    remoteValue: unknown,
    currentValue: unknown,
    localMutationRevision: number,
    requestRevision: number,
    preserveLocalAiSeatStatus = true
): boolean | undefined {
    const remoteStatus = normalizeAiSeatStatus(remoteValue)
    const currentStatus = normalizeAiSeatStatus(currentValue)

    if (remoteStatus === undefined) {
        return currentStatus
    }
    if (!preserveLocalAiSeatStatus) {
        return remoteStatus
    }
    if (currentStatus !== undefined && localMutationRevision > requestRevision) {
        return currentStatus
    }
    return remoteStatus
}
