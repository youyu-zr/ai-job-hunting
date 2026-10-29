/**
 * 从 BOSS 页面上的写法里取出数字区间：
 * "15-25K·13薪" → [15,25]，"100-499人" → [100,499]，"10000人以上" → [10000,∞]，
 * "20人以下" → [0,20]。日薪/时薪（"200-300元/天"）和"面议"认不出时返回 null，调用方应放行。
 */
export function parseLooseRange(value: string): [number, number] | null {
    const text = String(value || '').replace(/\s+/g, '');
    if (!text || /元\/(天|时|小时|周)|面议/.test(text)) {
        return null;
    }
    const match = text.match(/(\d+(?:\.\d+)?)(?:[-~至到](\d+(?:\.\d+)?))?/);
    if (!match) {
        return null;
    }
    const start = Number(match[1]);
    const end = match[2] === undefined ? undefined : Number(match[2]);
    if (!Number.isFinite(start) || (end !== undefined && (!Number.isFinite(end) || start > end))) {
        return null;
    }
    if (end !== undefined) {
        return [start, end];
    }
    if (text.includes('以下')) {
        return [0, start];
    }
    return [start, Number.POSITIVE_INFINITY];
}

/**
 * 用户配置的区间（"8-12"、"12以上"）与页面区间是否有重叠。
 * 页面写法认不出时返回 true（放行），不让一个格式问题把岗位整批跳过。
 */
export function isRangeOverlap(range: string, input: string): boolean {
    const wanted = parseLooseRange(range);
    const actual = parseLooseRange(input);
    if (!wanted || !actual) {
        return true;
    }
    return !(wanted[1] < actual[0] || actual[1] < wanted[0]);
}
