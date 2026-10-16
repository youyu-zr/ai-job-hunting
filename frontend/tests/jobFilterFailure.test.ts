import assert from 'node:assert/strict';
import test from 'node:test';

import {
    AiFilterProcessingError,
    aiFilterNonRetryableMessage,
    isAiFilterNonRetryableError,
    normalizeAiFilterFailure,
    handleAiFilterProcessingError,
} from '../src/platform/jobFilterFailure.ts';

test('AI screening failures have a distinct retryable error type', () => {
    const failure = new AiFilterProcessingError();

    assert.equal(failure.message, '岗位分析暂时不可用，请稍后重试');
    assert.equal(failure instanceof Error, true);
    assert.equal(new Error(failure.message) instanceof AiFilterProcessingError, false);
});

test('AI screening failure writes a run record and shows a warning', () => {
    const warnings: string[] = [];
    const notifications: Array<{type: string; message: string; duration: number}> = [];

    const handled = handleAiFilterProcessingError(new AiFilterProcessingError(), () => '运营专员-上海', {
        warn: message => warnings.push(message),
        notify: notification => notifications.push(notification),
    });

    assert.equal(handled, true);
    assert.deepEqual(warnings, [
        '工作【运营专员-上海】岗位分析暂时不可用，请稍后重试，本次未投递',
    ]);
    assert.deepEqual(notifications, [{
        type: 'warning',
        message: '运营专员-上海：岗位分析暂时不可用，请稍后重试',
        duration: 6000,
    }]);
});

test('ordinary errors do not trigger the AI screening warning', () => {
    const warnings: string[] = [];
    const notifications: Array<{type: string; message: string; duration: number}> = [];
    const handled = handleAiFilterProcessingError(
        new Error('岗位分析暂时不可用，请稍后重试'),
        () => '不应读取岗位标题',
        {
            warn: message => warnings.push(message),
            notify: notification => notifications.push(notification),
        },
    );

    assert.equal(handled, false);
    assert.deepEqual(warnings, []);
    assert.deepEqual(notifications, []);
});

test('AI filter permission and provider configuration errors are non-retryable', () => {
    for (const message of [
        'AI产品能力已过期',
        '请先获得内测权限后使用高级功能',
        '缺少产品能力【AI筛选】且试用次数已用完',
        '当前 AI 服务不支持分离系统规则与用户内容',
    ]) {
        assert.equal(isAiFilterNonRetryableError(message), true, message);
    }
    assert.equal(isAiFilterNonRetryableError('岗位分析暂时不可用，请稍后重试'), false);
});

test('only known permission or configuration errors keep their actionable message', () => {
    const permissionError = new Error('AI产品能力已过期');
    assert.equal(normalizeAiFilterFailure(permissionError), permissionError);
    assert.equal(normalizeAiFilterFailure('当前 AI 服务不支持分离系统规则与用户内容').message,
        '当前 AI 服务不支持分离系统规则与用户内容');
    assert.equal(normalizeAiFilterFailure(new Error('provider internal details')).message,
        '岗位分析暂时不可用，请稍后重试');
    assert.equal(aiFilterNonRetryableMessage('缺少产品能力【AI筛选】且试用次数已用完'),
        'AI 筛选权限不足，本轮投递已停止，请检查权限状态。');
    assert.equal(aiFilterNonRetryableMessage('当前 AI 服务不支持分离系统规则与用户内容'),
        '服务器 AI 配置不支持岗位分析，本轮投递已停止，请联系维护者。');
    assert.equal(aiFilterNonRetryableMessage('岗位分析暂时不可用，请稍后重试'), null);
});
