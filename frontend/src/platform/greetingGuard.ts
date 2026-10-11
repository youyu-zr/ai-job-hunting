export type GreetingJobContext = Record<string, unknown>;

const GENERIC_GREETING_FRAGMENTS = [
    '刚看到贵公司的招聘信息',
    '希望能与您有进一步的交流',
    '不知道是否方便进一步交流',
    '对岗位感兴趣，希望沟通',
];

const IGNORED_ANCHORS = new Set([
    '未提供',
    '未读取到',
    '招聘信息',
    '岗位信息',
    '当前岗位',
    '职位描述',
    '工作内容',
    '岗位职责',
    '任职要求',
    '工作',
    '岗位',
    '职位',
    '公司',
    '职责',
    '要求',
    '不限',
]);

function normalizeText(value: unknown): string {
    return String(value ?? '').replace(/\s+/g, '').trim();
}

function cleanGreetingText(value: unknown): string {
    return String(value ?? '')
        .replace(/```(?:text|markdown)?/gi, '')
        .replace(/```/g, '')
        .replace(/[\r\n]+/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
}

/** Reads the text from both Axios's response shape and the plain data shape. */
export function extractGreetingText(response: unknown): string {
    let value: unknown = response;
    for (let depth = 0; depth < 4; depth += 1) {
        if (typeof value === 'string') {
            return cleanGreetingText(value);
        }
        if (!value || typeof value !== 'object' || !('data' in value)) {
            return '';
        }
        value = (value as {data?: unknown}).data;
    }
    return '';
}

function isConcreteAnchor(value: string): boolean {
    const normalized = normalizeText(value);
    if (normalized.length < 2 || normalized.length > 60 || IGNORED_ANCHORS.has(normalized)) {
        return false;
    }
    return !/^[\d._:/-]+$/.test(normalized);
}

function collectAnchors(value: unknown, anchors: string[] = [], key = ''): string[] {
    if (value === null || value === undefined || key === 'jobKey') {
        return anchors;
    }
    if (Array.isArray(value)) {
        value.forEach(item => collectAnchors(item, anchors, key));
        return anchors;
    }
    if (typeof value === 'object') {
        Object.entries(value as Record<string, unknown>).forEach(([entryKey, entryValue]) => {
            collectAnchors(entryValue, anchors, entryKey);
        });
        return anchors;
    }

    const text = String(value).trim();
    if (!text) {
        return anchors;
    }
    const candidates = [
        text,
        ...text.split(/[\s,，。；;、:：!?！？()（）\[\]【】/\\|]+/),
    ];
    candidates.forEach(candidate => {
        const normalized = normalizeText(candidate);
        if (isConcreteAnchor(normalized) && !anchors.includes(normalized)) {
            anchors.push(normalized);
        }
    });
    return anchors;
}

/** Prevents an old/generic response from reaching the platform send call. */
export function isUsableJobGreeting(greeting: unknown, jobInfo: GreetingJobContext): boolean {
    const normalizedGreeting = normalizeText(cleanGreetingText(greeting));
    if (!normalizedGreeting || GENERIC_GREETING_FRAGMENTS.some(fragment => normalizedGreeting.includes(normalizeText(fragment)))) {
        return false;
    }
    const anchors = collectAnchors(jobInfo);
    return anchors.some(anchor => normalizedGreeting.includes(anchor));
}
