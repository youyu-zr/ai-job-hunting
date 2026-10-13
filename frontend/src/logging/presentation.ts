const TRACE_FIELDS = ['status', 'traceId', 'jobKey', 'bossId', 'action', 'channel', 'cmid', 'msg', 'content'];

function sanitizeLogText(value: unknown): string {
    return String(value ?? '').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, ' ');
}

function normalizeLogText(value: unknown): string {
    return sanitizeLogText(value).replace(/\s+/g, ' ').trim();
}

const STATUS_LABELS: Record<string, string> = {
    GENERATED: '已生成',
    QUEUED: '已进入发送队列',
    INPUT_FILLED: '已准备发送',
    SEND_CLICKED: '已尝试发送',
    SEND_SUBMITTED: '已提交，等待平台确认',
    SEND_CONFIRMED: '已确认发送',
    UNCONFIRMED: '未确认，需人工核实',
    FAILED: '发送失败',
};

const ACTION_LABELS: Record<string, string> = {
    POST_DELIVERY_GREETING: '投递后招呼语',
    POST_DELIVERY_IMAGE: '投递后图片',
    AI_REPLY: 'AI回复',
    RESUME_SEND: '简历发送',
};

const FILTER_ACTION_LABELS: Record<string, string> = {
    DELIVER: '允许投递',
    CHAT_FIRST: '先投递并沟通确认',
    FILTER: '过滤',
    HUMAN_REVIEW: '暂停人工确认',
};

const RISK_LABELS: Record<string, string> = {
    LOW: '低风险',
    MEDIUM: '中风险',
    HIGH: '高风险',
};

const INTERNAL_LOG_TRANSLATIONS: Array<[string, string]> = [
    ['load user preference config from server', '正在从服务器加载用户偏好'],
    ['load user preference config', '正在加载用户偏好'],
    ['loaded server config', '已加载服务器配置'],
    ['loaded global latest mirror', '已加载最新本地配置'],
    ['loaded local mirror config', '已加载本地配置'],
    ['beta access load failed', '读取试用状态失败'],
    ['server config load failed; try local mirror', '服务器配置读取失败，正在尝试本地配置'],
    ['load config failed: no server data and no local mirror', '配置读取失败，服务器和本地配置均不可用'],
    ['message prepared', '已准备发送'],
    ['confirmed by own websocket echo cmid', '平台已确认发送'],
    ['confirmed by own websocket echo text', '平台已确认发送'],
    ['acknowledged by platform', '平台已确认发送'],
    ['WS Hook Start', '消息通道初始化'],
    ['WS Hook success', '消息通道连接成功'],
    ['WS Send Hook Start', '发送通道初始化'],
    ['WS Send Hook Success', '发送通道连接成功'],
    ['WS Send Hook install；wait ws send', '发送通道已安装，等待平台发送'],
];

function readTraceField(message: string, field: string): string {
    const marker = `${field}=`;
    const start = message.indexOf(marker);
    if (start < 0) {
        return '';
    }

    const valueStart = start + marker.length;
    const remaining = message.slice(valueStart);
    const nextField = new RegExp(`\\s(?:${TRACE_FIELDS.filter(item => item !== field).join('|')})=`);
    const nextIndex = remaining.search(nextField);
    return (nextIndex < 0 ? remaining : remaining.slice(0, nextIndex)).trim();
}

function failureReason(message: string): string {
    const normalized = message.toLowerCase();
    if (normalized.includes('sender unavailable') || normalized.includes('no available message sender')) {
        return '当前页面消息通道未就绪';
    }
    if (normalized.includes('websocket') || normalized.includes('client.send')) {
        return '页面消息通道发送失败';
    }
    if (normalized.includes('before timeout')) {
        return '未收到平台回显，请到BOSS会话里确认是否已发出';
    }
    return '发送未完成，请刷新BOSS页面后重试';
}

/** Converts internal trace output into a concise, user-facing Chinese log entry. */
export function formatUserVisibleLog(message: string): string {
    const raw = sanitizeLogText(message || '');
    if (!raw.startsWith('[SEND_STATE]')) {
        const legacyFilterRecord = normalizeLegacyFilterRecord(raw);
        if (legacyFilterRecord !== raw) {
            return legacyFilterRecord;
        }
        const lowerRaw = raw.toLowerCase();
        const translation = INTERNAL_LOG_TRANSLATIONS.find(([source]) => lowerRaw.includes(source.toLowerCase()));
        return translation ? raw.replace(new RegExp(translation[0], 'ig'), translation[1]) : raw;
    }

    const status = readTraceField(raw, 'status').toUpperCase();
    if (status !== 'SEND_SUBMITTED' && status !== 'SEND_CONFIRMED' && status !== 'UNCONFIRMED' && status !== 'FAILED') {
        return '';
    }
    const action = readTraceField(raw, 'action').toUpperCase();
    const jobKey = readTraceField(raw, 'jobKey');
    const detail = readTraceField(raw, 'msg');
    const jobPrefix = jobKey ? `岗位【${jobKey}】` : '';
    const actionLabel = ACTION_LABELS[action] || '消息';
    const statusLabel = STATUS_LABELS[status] || '发送状态已更新';
    const suffix = status === 'FAILED' || status === 'UNCONFIRMED'
        ? `：${failureReason(detail)}`
        : '';
    return `${jobPrefix}${actionLabel}${statusLabel}${suffix}`;
}

export type DeliverySuccessLogDetails = {
    companyName?: unknown;
    jobName?: unknown;
    salaryDesc?: unknown;
    location?: unknown;
    reason?: unknown;
};

/** Keeps the delivery result, complete job context, and screening explanation together. */
export function formatDeliverySuccessLog(details: DeliverySuccessLogDetails): string {
    const normalize = normalizeLogText;
    const companyName = normalize(details.companyName) || '未知公司';
    const jobName = normalize(details.jobName) || '未知岗位';
    const salaryDesc = normalize(details.salaryDesc);
    const location = normalize(details.location);
    const reason = normalize(details.reason);
    const fields = [
        `公司【${companyName}】`,
        `岗位【${jobName}】投递成功`,
        salaryDesc ? `薪资：${salaryDesc}` : '',
        location ? `地点：${location}` : '',
        reason ? `原因：${reason}` : '',
    ].filter(Boolean);
    return fields.join('；');
}

/** Converts persisted "reason + JSON" filter records from older releases. */
export function normalizeLegacyFilterRecord(message: string): string {
    const raw = String(message || '');
    const jsonStart = raw.indexOf('{');
    if (jsonStart < 0 || !raw.trimEnd().endsWith('}')) {
        return raw;
    }
    const prefix = raw.slice(0, jsonStart).replace(/；\s*$/, '').trimEnd();
    const jsonText = raw.slice(jsonStart).trim();
    try {
        const summary = formatAiFilterSummary(JSON.parse(jsonText));
        return summary ? `${prefix}；${summary}` : prefix;
    } catch {
        return raw;
    }
}

/** Render structured AI screening data as a complete, Chinese user-facing summary. */
export function formatAiFilterSummary(data: unknown): string {
    if (data === undefined || data === null || data === '') {
        return '';
    }
    let value: any = data;
    if (typeof data === 'string') {
        try {
            value = JSON.parse(data);
        } catch {
            return normalizeLogText(data);
        }
    }
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
        return normalizeLogText(value);
    }

    const lines: string[] = [];
    if (typeof value.filter === 'boolean') {
        lines.push(`AI结论：${value.filter ? '过滤' : '保留'}`);
    }
    const action = String(value.recommendedAction || '').trim().toUpperCase();
    if (action) {
        lines.push(`处理建议：${FILTER_ACTION_LABELS[action] || '待确认'}`);
    }
    const risk = String(value.riskLevel || '').trim().toUpperCase();
    if (risk) {
        lines.push(`风险等级：${RISK_LABELS[risk] || '待确认'}`);
    }
    if (value.reason) {
        lines.push(`原因：${normalizeLogText(value.reason)}`);
    }
    const appendList = (label: string, raw: unknown) => {
        if (Array.isArray(raw) && raw.length > 0) {
            const values = raw.filter(Boolean).map(normalizeLogText).filter(Boolean);
            if (values.length > 0) {
                lines.push(`${label}：${values.join('、')}`);
            }
        }
    };
    appendList('正向信息', value.goodSignals);
    appendList('风险信息', value.riskSignals);
    appendList('建议确认', value.prepSkills);
    if (Number.isFinite(value.jobScore) || Number.isFinite(value.companyScore) || Number.isFinite(value.matchScore)) {
        const scores = [
            Number.isFinite(value.jobScore) ? `岗位${value.jobScore}` : '',
            Number.isFinite(value.companyScore) ? `公司${value.companyScore}` : '',
            Number.isFinite(value.matchScore) ? `匹配${value.matchScore}` : '',
        ].filter(Boolean);
        lines.push(`评分：${scores.join('，')}`);
    }
    return lines.join('；');
}
