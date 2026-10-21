import {ConversationPauseRegistry} from './pauseRegistry.ts';

export type AiSeatRuntimeStatus = {
    globalEnabled: boolean;
    manuallyPaused: boolean;
    gatePaused: boolean;
    globalRevision: number;
    sessionRevision: number;
};

export function canScanUnreadConversations(authoritativeSeatEnabled: boolean | null): boolean {
    return authoritativeSeatEnabled === true;
}

export function parseAiSeatRuntimeStatus(value: unknown): AiSeatRuntimeStatus | null {
    if (!value || typeof value !== 'object') {
        return null;
    }
    const data = value as Record<string, unknown>;
    const globalRevision = Number(data.globalRevision);
    const sessionRevision = Number(data.sessionRevision);
    if (typeof data.globalEnabled !== 'boolean'
        || typeof data.manuallyPaused !== 'boolean'
        || typeof data.gatePaused !== 'boolean'
        || !Number.isSafeInteger(globalRevision)
        || globalRevision < 0
        || !Number.isSafeInteger(sessionRevision)
        || sessionRevision < 0) {
        return null;
    }
    return {
        globalEnabled: data.globalEnabled,
        manuallyPaused: data.manuallyPaused,
        gatePaused: data.gatePaused,
        globalRevision,
        sessionRevision,
    };
}


export type AiSeatTurnDeliveryInput = {
    aiSeatEnabled: boolean;
    turnSeatRevision: number;
    currentSeatRevision: number;
    jobKey: string;
    turnGeneration: number;
    serverStatusFresh: boolean;
    serverSeatEnabled: boolean;
    serverSessionPaused: boolean;
    serverGatePaused: boolean;
    turnServerRevision: number;
    currentServerRevision: number;
    turnSessionRevision: number;
    currentSessionRevision: number;
};

export function canDeliverAiSeatTurn(input: AiSeatTurnDeliveryInput): boolean {
    return input.aiSeatEnabled
        && input.turnSeatRevision === input.currentSeatRevision
        && input.serverStatusFresh
        && input.serverSeatEnabled
        && !input.serverSessionPaused
        && !input.serverGatePaused
        && input.turnServerRevision === input.currentServerRevision
        && input.turnSessionRevision === input.currentSessionRevision
        && ConversationPauseRegistry.canDeliver(input.jobKey, input.turnGeneration);
}
