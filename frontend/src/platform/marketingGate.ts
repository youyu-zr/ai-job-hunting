const DIRECT_NEGATION_PREFIXES = [
    '不接受', '不支持', '不需要', '不要求', '不涉及', '不包括', '不含',
    '不用', '无需', '没有', '禁止', '避免', '不做', '不是', '非', '无', '不',
];

const SCOPED_NEGATION_PREFIXES = [
    '不涉及', '不包括', '不含', '不做', '禁止', '避免', '没有', '不是', '非',
    '不要求', '不需要', '无需', '不用', '不收取', '不缴纳', '不交纳', '不交', '不缴',
];

const NEGATION_BREAKERS = [
    '但是', '但', '不过', '然而', '却', '另有', '另外', '同时', '并且', '以及',
    '也有', '还有', '还需', '仍然', '仍', '需要', '需', '会', '包含', '存在',
];

const HARD_PROMOTIONAL_RISKS = [
    '先交费', '先缴费', '培训贷', '押金', '保证金',
    '培训费', '设备费', '报名费', '付费培训', '购买课程', '付款后',
    '传销', '拉人头', '团队裂变', '层级返利', '邀请好友返佣',
    '发展下线', '代理加盟', '招商加盟',
    '刷单', '刷流水', '账号租借', '租账号', '账号买卖', '买卖账号',
    '购买账号', '账号交易', '代实名', '博彩', '代充',
];

const PROMOTION_CONTEXT = [
    '推广', '宣传', '营销', '广告', '课程', '培训', '代理', '加盟',
    '招商', '返佣', '拉新', '裂变', '招聘广告', '资料领取', '获取资料', '项目',
];

const FUNNEL_ACTIONS = [
    '扫码进群', '扫码加群', '扫码领取', '扫码报名', '扫码下载', '扫码了解',
    '扫描二维码', '进群领取', '加群领取', '注册后', '注册即可', '注册领取',
    '下载后', '点击链接', '填写邀请码', '邀请好友', '成为代理', '加入社群',
    '添加客服', '加客服', '加老师', '联系导师', '添加微信', '加微信',
    '微信领取', '领取课程资料', '关注公众号',
    '请扫码', '请注册', '请下载', '请进群', '请加群', '请领取',
    '报名即可', '报名领取', '付款后', '缴费后', '先充值',
];

const CANDIDATE_DIRECTED_ACTIONS = [
    '请你', '请您', '应聘者', '求职者', '领取资料', '填写邀请码',
    '添加客服', '加客服', '微信领取', '领取课程资料',
    '邀请好友', '成为代理',
];

function compact(text: string): string {
    return String(text || '').replace(/\s+/g, '');
}

function currentClausePrefix(text: string, index: number): string {
    const start = Math.max(0, index - 28);
    const prefix = text.slice(start, index);
    const clauseStart = Math.max(
        ...['，', ',', '。', '.', '；', ';', '！', '!', '？', '?', '\n', '\r']
            .map(delimiter => prefix.lastIndexOf(delimiter)),
    );
    return prefix.slice(clauseStart + 1).replace(/[\s\u3000:：/|、]+/g, '');
}

function isNegatedOccurrence(text: string, index: number): boolean {
    const prefix = currentClausePrefix(text, index);
    if (DIRECT_NEGATION_PREFIXES.some(term => prefix.endsWith(term))) {
        return true;
    }
    return SCOPED_NEGATION_PREFIXES.some(term => {
        const at = prefix.lastIndexOf(term);
        if (at < 0) {
            return false;
        }
        const scope = prefix.slice(at + term.length);
        return scope.length <= 16 && !NEGATION_BREAKERS.some(breaker => scope.includes(breaker));
    });
}

function containsUnnegatedAny(text: string, terms: string[]): boolean {
    return terms.some(term => {
        let index = text.indexOf(term);
        while (index >= 0) {
            if (!isNegatedOccurrence(text, index)) {
                return true;
            }
            index = text.indexOf(term, index + term.length);
        }
        return false;
    });
}

function isHardPromotionalRisk(text: string): boolean {
    const membershipFeeRisk = text
        .split(/[，,。.;；!！?？\n\r]/)
        .some(clause => containsUnnegatedAny(clause, ['入会费'])
            && containsUnnegatedAny(clause, ['缴纳', '交纳', '先交', '先缴', '支付', '收取', '交费', '缴费']));
    return membershipFeeRisk || containsUnnegatedAny(text, HARD_PROMOTIONAL_RISKS);
}

function isPromotionalMessage(text: string): boolean {
    const normalized = compact(text);
    if (!normalized) {
        return false;
    }
    if (isHardPromotionalRisk(normalized)) {
        return true;
    }
    const hasPromotionContext = containsUnnegatedAny(normalized, PROMOTION_CONTEXT);
    const hasFunnelAction = containsUnnegatedAny(normalized, FUNNEL_ACTIONS);
    if (!hasPromotionContext || !hasFunnelAction) {
        return false;
    }
    const hasJobDutyContext = containsUnnegatedAny(normalized,
        ['岗位职责', '工作职责', '日常工作', '主要工作', '工作内容', '负责']);
    const hasCandidateDirectedAction = containsUnnegatedAny(normalized, CANDIDATE_DIRECTED_ACTIONS);
    return !hasJobDutyContext || hasCandidateDirectedAction;
}

/**
 * Local default-off guard protects users when a newer browser script briefly
 * talks to a backend that has not yet received the authoritative server gate.
 */
export function shouldSilencePromotionalMessage(
    message: string,
    marketingReplyEnabled: boolean,
): boolean {
    if (isHardPromotionalRisk(compact(message))) {
        return true;
    }
    return !marketingReplyEnabled && isPromotionalMessage(message);
}
