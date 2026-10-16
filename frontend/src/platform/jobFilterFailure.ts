export const AI_FILTER_FAILURE_MESSAGE = '岗位分析暂时不可用，请稍后重试';

export class AiFilterProcessingError extends Error {
    constructor() {
        super(AI_FILTER_FAILURE_MESSAGE);
        this.name = 'AiFilterProcessingError';
    }
}

export function isAiFilterNonRetryableError(error: unknown): boolean {
    const message = error instanceof Error
        ? error.message
        : typeof error === 'string' ? error : '';
    return message.includes('AI产品能力已过期')
        || message.includes('请先获得内测权限后使用高级功能')
        || message.includes('缺少产品能力')
        || message.includes('当前 AI 服务不支持分离系统规则与用户内容');
}

export function aiFilterNonRetryableMessage(error: unknown): string | null {
    const message = error instanceof Error
        ? error.message
        : typeof error === 'string' ? error : '';
    if (message.includes('当前 AI 服务不支持分离系统规则与用户内容')) {
        return '服务器 AI 配置不支持岗位分析，本轮投递已停止，请联系维护者。';
    }
    if (isAiFilterNonRetryableError(error)) {
        return 'AI 筛选权限不足，本轮投递已停止，请检查权限状态。';
    }
    return null;
}

export function normalizeAiFilterFailure(error: unknown): Error {
    if (isAiFilterNonRetryableError(error)) {
        return error instanceof Error ? error : new Error(String(error));
    }
    return new AiFilterProcessingError();
}

export type AiFilterFailureNotification = {
    type: 'warning';
    message: string;
    duration: number;
};

export type AiFilterFailureEffects = {
    warn: (message: string) => void;
    notify: (notification: AiFilterFailureNotification) => void;
};

export function handleAiFilterProcessingError(
    error: unknown,
    getJobTitle: () => string,
    effects: AiFilterFailureEffects,
): boolean {
    if (!(error instanceof AiFilterProcessingError)) {
        return false;
    }

    const jobTitle = getJobTitle();
    effects.warn(`工作【${jobTitle}】${AI_FILTER_FAILURE_MESSAGE}，本次未投递`);
    effects.notify({
        type: 'warning',
        message: `${jobTitle}：${AI_FILTER_FAILURE_MESSAGE}`,
        duration: 6000,
    });
    return true;
}
