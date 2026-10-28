/**
 * 投递前的确定性硬条件检查：不依赖 AI，在 AI 筛选之前执行。
 *
 * 原则：只拦「能可靠判断」的情况，认不准就放行交给 AI（AI 那边也拿到同一份硬条件文字）。
 * - 不投的公司：去掉「有限公司/集团」等后缀后，页面公司名包含用户填写的名字，或两者相同才算。
 *   不做反向包含：用户填「腾讯音乐娱乐」时不能把所有「腾讯」岗位都拦掉。
 * - 坚决不做：只在关闭 AI 过滤时按岗位名称字面匹配。开着 AI 时交给 AI 判断，
 *   因为「售前技术支持(非销售)」「客服系统Java开发」这类名字字面会命中「销售」「客服」。
 * - 薪资底线：只在页面写的是明确的月薪区间或单值（K/千/万）时判断，上限低于底线才不投；
 *   「以上/起」、日薪/周薪/年薪、底薪+提成、面议、没有单位，一律放行。
 * 「其他底线」（单休、大小周等）需要读懂语义，只交给 AI。
 */

export type HardConditionPreference = {
    blockedCompanies?: string[];
    avoidTags?: string[];
    salaryFloorK?: number | null;
};

export type HardConditionJob = {
    brandName?: string;
    jobName?: string;
    salaryDesc?: string;
};

export type HardConditionHit = {
    reason: string;
    data: string;
};

export type HardConditionOptions = {
    /** 开着 AI 过滤时，「坚决不做」交给 AI。 */
    semanticScreeningEnabled?: boolean;
};

const COMPANY_SUFFIX = /(股份有限公司|有限责任公司|有限公司|集团|公司)$/;
const GENERIC_COMPANY_WORDS = /^(科技|网络|信息|技术|网络科技|信息技术|电子|软件|文化|传媒|教育|咨询|贸易|实业)$/;

function normalize(value: unknown): string {
    return String(value ?? '').replace(/\s+/g, '').toLowerCase();
}

function cleanList(values: unknown): string[] {
    if (!Array.isArray(values)) {
        return [];
    }
    return values.map(normalize).filter(item => item.length >= 2);
}

/**
 * 黑名单单向匹配：页面文字包含词条才算。词条少于 2 个字、页面文字为空时不算，
 * 避免填一个字母就拦掉所有岗位，或页面没读到名字时整批跳过。
 */
export function matchesExcludeList(values: unknown, input: unknown): string | null {
    const text = normalize(input);
    if (!text) {
        return null;
    }
    return cleanList(values).find(entry => text.includes(entry)) ?? null;
}

/** "12-18K·13薪" → 18，"8千-1.2万" → 12，"1-1.5万" → 15，"15K" → 15；认不准 → null。 */
export function monthlySalaryUpperK(salaryDesc: unknown): number | null {
    let text = String(salaryDesc ?? '').replace(/\s+/g, '');
    // 「·13薪」是年终月数，不是金额。
    text = text.replace(/[·・.]?\d+薪/g, '');
    if (!text
        || /以上|起|面议|底薪|提成|\+/.test(text)
        || /\/(天|日|时|小时|周|年)|元\/|年薪|日薪|时薪|周薪/.test(text)) {
        return null;
    }
    const match = text.match(/^(\d+(?:\.\d+)?)(k|千|万|w)?(?:[-~至到](\d+(?:\.\d+)?)(k|千|万|w)?)?(\/月)?$/i);
    if (!match) {
        return null;
    }
    const [, lowValue, lowUnit, highValue, highUnit] = match;
    const upperValue = highValue ?? lowValue;
    const upperUnit = highValue ? highUnit : lowUnit;
    if (!upperUnit) {
        return null;
    }
    const upper = /万|w/i.test(upperUnit) ? Number(upperValue) * 10 : Number(upperValue);
    return Number.isFinite(upper) && upper > 0 ? upper : null;
}

export function checkHardConditions(
    preference: HardConditionPreference | null | undefined,
    job: HardConditionJob,
    options: HardConditionOptions = {},
): HardConditionHit | null {
    const pref = preference || {};
    const brand = normalize(job.brandName);
    if (brand) {
        const bareBrand = brand.replace(COMPANY_SUFFIX, '');
        const blocked = cleanList(pref.blockedCompanies)
            .map(name => name.replace(COMPANY_SUFFIX, ''))
            .filter(name => name.length >= 2)
            .find(name => {
                if (bareBrand === name) {
                    return true;
                }
                // 两个字的名字（京东、华为）只认开头，免得「北京东方」被「京东」拦；
                // 只剩通用词（科技、网络…）时必须完全相同，免得拦掉所有科技公司。
                if (name.length <= 2 || GENERIC_COMPANY_WORDS.test(name)) {
                    return name.length <= 2 && !GENERIC_COMPANY_WORDS.test(name) && brand.startsWith(name);
                }
                return brand.includes(name);
            });
        if (blocked) {
            return {reason: '在「不投的公司」名单里', data: String(job.brandName)};
        }
    }

    if (!options.semanticScreeningEnabled) {
        const avoid = matchesExcludeList(pref.avoidTags, job.jobName);
        if (avoid) {
            return {reason: `岗位名称含「坚决不做」的「${avoid}」`, data: String(job.jobName)};
        }
    }

    const floor = Number(pref.salaryFloorK);
    if (Number.isFinite(floor) && floor > 0) {
        const upper = monthlySalaryUpperK(job.salaryDesc);
        if (upper !== null && upper < floor) {
            return {reason: `月薪上限 ${upper}K 低于你的底线 ${floor}K`, data: String(job.salaryDesc)};
        }
    }
    return null;
}
