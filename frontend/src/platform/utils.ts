import {GM_getValue, GM_setValue, GM_notification, GM_addValueChangeListener, GM_xmlhttpRequest, unsafeWindow} from "$";
import {messageFingerprint} from "./messageFingerprint";

export class Tools {

    public static window: any = unsafeWindow

    /**
     * 模糊匹配
     * @param arr
     * @param input
     * @param emptyStatus
     * @returns {boolean|*}
     */
    public static fuzzyMatch(arr: string[], input: string, emptyStatus: boolean): boolean {
        if (arr.length === 0) {
            // 为空时直接返回指定的空状态
            return emptyStatus;
        }
        input = input.toLowerCase();
        let emptyEle = false;
        // 遍历数组中的每个元素
        for (let i = 0; i < arr.length; i++) {
            // 如果当前元素包含指定值，则返回 true
            let arrEleStr = arr[i].toLowerCase();
            if (arrEleStr.length === 0) {
                emptyEle = true;
                continue;
            }
            if (arrEleStr.includes(input) || input.includes(arrEleStr)) {
                return true;
            }
        }

        // 所有元素均为空元素【返回空状态】
        if (emptyEle) {
            return emptyStatus;
        }

        // 如果没有找到匹配的元素，则返回 false
        return false;
    }


    // 范围匹配
    public static isSalaryRangeInputValid(value: string): boolean {
        const normalized = String(value || '').trim();
        const match = normalized.match(/^(\d+(?:\.\d+)?)\s*(?:-\s*(\d+(?:\.\d+)?))?\s*(以上|以下)?$/);
        if (!match) {
            return false;
        }
        const start = Number(match[1]);
        const end = match[2] === undefined ? undefined : Number(match[2]);
        if (!Number.isFinite(start) || (end !== undefined && (!Number.isFinite(end) || start > end))) {
            return false;
        }
        return (end !== undefined && match[3] === undefined)
            || (end === undefined && match[3] !== undefined);
    }

    public static isRangeOverlap(range: string, input: string): boolean {
        const parseRange = (str: string): [number, number] => {
            const match = String(str || '').match(/^\s*(\d+(?:\.\d+)?)\s*(?:-\s*(\d+(?:\.\d+)?))?\s*(以上|以下)?(?:\s*[kK])?\s*$/);
            if (!match) {
                throw new Error("Invalid range format");
            }
            const start = parseFloat(match[1]);
            if (match[3] === '以下') {
                return [0, start];
            }
            const end = match[2] ? parseFloat(match[2]) : Number.POSITIVE_INFINITY;
            return [start, end];
        };

        const [rangeStart, rangeEnd] = parseRange(range);
        const [inputStart, inputEnd] = parseRange(input);

        return !(rangeEnd < inputStart || inputEnd < rangeStart);
    }

    public static getRandomNumber(startMs: number, endMs: number) {
        return Math.floor(Math.random() * (endMs - startMs + 1)) + startMs;
    }

    public static getCookieValue(key: string) {
        const cookies = document.cookie.split(';');
        for (const cookie of cookies) {
            const [cookieKey, cookieValue] = cookie.trim().split('=');
            if (cookieKey === key) {
                return decodeURIComponent(cookieValue);
            }
        }
        return null;
    }

    public static parseURL(url: string) {
        const urlObj = new URL(url);
        const pathSegments = urlObj.pathname.split('/');
        const jobId = pathSegments[2].replace('.html', '');
        const lid = urlObj.searchParams.get('lid');
        const securityId = urlObj.searchParams.get('securityId');

        return {
            securityId,
            jobId,
            lid
        };
    }

    public static queryString(baseURL: string, queryParams: string) {
        const queryString = Object.entries(queryParams)
            .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
            .join('&');

        return `${baseURL}?${queryString}`;
    }

    static getCurDay() {
        // 创建 Date 对象获取当前时间
        const currentDate = new Date();

        // 获取年、月、日、小时、分钟和秒
        const year = currentDate.getFullYear();
        const month = String(currentDate.getMonth() + 1).padStart(2, '0');
        const day = String(currentDate.getDate()).padStart(2, '0');

        // 格式化时间字符串
        return `${year}-${month}-${day}`;
    }

    // 等待一段时间的函数
    public static sleep(ms: number) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    public static getEndChar():string {
        return String.fromCharCode(0x00)
    }

}


export class TampermonkeyApi {
    static CUR_CK = ""
    static LOCAL_CONFIG = "config";
    static PUSH_SUCCESS_COUNT = "pushSuccessCount:" + Tools.getCurDay();
    static PUSH_FAIL_COUNT = "pushFailCount:" + Tools.getCurDay();
    static ACTIVE_ENABLE = "activeEnable";
    static PUSH_LIMIT = "push_limit" + Tools.getCurDay();
    // 投递锁是否被占用，可重入；value表示当前正在投递的job
    static PUSH_LOCK = "push_lock";

    // 公司名包含输入框lab
    static cnInKey = "companyNameInclude"
    // 公司名排除输入框lab
    static cnExKey = "companyNameExclude"
    // job名称包含输入框lab
    static jnInKey = "jobNameInclude"
    // job内容排除输入框lab
    static jcExKey = "jobContentExclude"
    // 薪资范围输入框lab
    static srInKey = "salaryRange"
    // 公司规模范围输入框lab
    static csrInKey = "companyScaleRange"
    // 自定义招呼语输入框
    static sgInKey = "sendSelfGreet"
    static SEND_SELF_GREET_MEMORY = "sendSelfGreetMemory"

    constructor() {
        // fix 还未创建对象时，CUR_CK为空字符串，创建完对象之后【如果没有配置，则为null】导致key前缀不一致
        TampermonkeyApi.CUR_CK = GM_getValue("ck_cur", "");
    }

    static GmSetValue(key: string, val: any) {
        return GM_setValue(TampermonkeyApi.CUR_CK + key, val);
    }

    static GmGetValue(key: string, defVal: any) {
        return GM_getValue(TampermonkeyApi.CUR_CK + key, defVal);
    }

    static GMXmlHttpRequest(options: any) {
        return GM_xmlhttpRequest(options)
    }

    static GmAddValueChangeListener(key: string, func: any) {
        return GM_addValueChangeListener(TampermonkeyApi.CUR_CK + key, func);
    }

    static GmNotification(content: string) {
        GM_notification({
            title: "Boss直聘批量投简历",
            text: content,
            highlight: true, // 布尔值，是否突出显示发送通知的选项卡
            silent: true, // 布尔值，是否播放声音
            timeout: 10000, // 设置通知隐藏时间
            onclick: function () {
                // console.log("点击了通知");
            },
            ondone() {
            }, // 在通知关闭（无论这是由超时还是单击触发）或突出显示选项卡时调用
        });
    }
}

interface CacheEntry {
    expiration: number; // 过期时间戳
    completed?: boolean;
    replySent?: boolean;
    pendingOperation?: string;
}

/**
 * 消息去重缓存类
 */
export class MessageCache {
    private static readonly DEFAULT_EXPIRATION = 60 * 1000; // 默认过期时间：1分钟
    private static readonly CACHE_KEY = "messageCache"; // GM存储键

    /**
     * 获取当前缓存数据
     */
    private getCache(): Record<string, CacheEntry> {
        const rawCache = GM_getValue(this.getCacheKey(), "{}");
        try {
            return JSON.parse(rawCache) as Record<string, CacheEntry>;
        } catch {
            return {};
        }
    }

    private getCacheKey(): string {
        const accountKey = String(GM_getValue("ck_cur", "") || "").trim();
        return accountKey ? `${MessageCache.CACHE_KEY}:${accountKey}` : MessageCache.CACHE_KEY;
    }

    /**
     * 保存缓存数据
     */
    private saveCache(cache: Record<string, CacheEntry>): void {
        GM_setValue(this.getCacheKey(), JSON.stringify(cache));
    }

    /**
     * 清理过期的缓存
     */
    private cleanExpiredCache(cache: Record<string, CacheEntry>): Record<string, CacheEntry> {
        const now = Date.now();
        const validCache: Record<string, CacheEntry> = {};

        Object.entries(cache).forEach(([key, entry]) => {
            if (entry.expiration > now) {
                validCache[key] = entry;
            }
        });

        return validCache;
    }

    /**
     * 检查是否处理过消息
     * @param bossId - Boss ID
     * @param text - 完整消息内容
     * @returns 是否已处理
     */
    public isMessageProcessed(bossId: number, text: string): boolean {
        const key = this.generateKey(bossId, text);
        const cache = this.getCache();
        const validCache = this.cleanExpiredCache(cache);

        this.saveCache(validCache); // 更新有效缓存
        const entry = validCache[key];
        if (!entry) {
            return false;
        }
        // A partially completed action must be retried. In-flight markers and
        // fully completed markers suppress duplicate work.
        if (entry.completed === true || entry.completed === undefined) {
            return true;
        }
        return entry.replySent !== true && !entry.pendingOperation;
    }

    /**
     * 标记消息为已处理
     * @param bossId - Boss ID
     * @param text - 完整消息内容
     * @param expiration - 过期时间，单位毫秒（可选，默认 1 分钟）
     */
    public markMessageAsProcessed(bossId: number, text: string, expiration?: number): void {
        const key = this.generateKey(bossId, text);
        const cache = this.getCache();
        const previous = cache[key];

        cache[key] = {
            expiration: Date.now() + (expiration || MessageCache.DEFAULT_EXPIRATION),
            completed: false,
            replySent: previous?.replySent === true,
            pendingOperation: previous?.pendingOperation,
        };

        this.saveCache(cache); // 持久化缓存
    }

    public markMessageAsCompleted(bossId: number, text: string, expiration?: number): void {
        const key = this.generateKey(bossId, text);
        const cache = this.getCache();
        cache[key] = {
            expiration: Date.now() + (expiration || MessageCache.DEFAULT_EXPIRATION),
            completed: true,
            replySent: true,
        };
        this.saveCache(cache);
    }

    public markReplySent(bossId: number, text: string, expiration?: number): void {
        const key = this.generateKey(bossId, text);
        const cache = this.getCache();
        const previous = cache[key];
        cache[key] = {
            expiration: Date.now() + (expiration || MessageCache.DEFAULT_EXPIRATION),
            completed: previous?.completed === true,
            replySent: true,
            pendingOperation: previous?.pendingOperation,
        };
        this.saveCache(cache);
    }

    public wasReplySent(bossId: number, text: string): boolean {
        const key = this.generateKey(bossId, text);
        const cache = this.getCache();
        return cache[key]?.replySent === true && cache[key]?.completed !== true;
    }

    public markPendingOperation(
        bossId: number,
        text: string,
        operation: string,
        expiration?: number,
    ): void {
        const key = this.generateKey(bossId, text);
        const cache = this.getCache();
        const previous = cache[key];
        cache[key] = {
            expiration: Date.now() + (expiration || MessageCache.DEFAULT_EXPIRATION),
            completed: false,
            replySent: previous?.replySent === true,
            pendingOperation: operation,
        };
        this.saveCache(cache);
    }

    public hasPendingOperation(bossId: number, text: string, operation: string): boolean {
        const key = this.generateKey(bossId, text);
        const cache = this.getCache();
        return cache[key]?.completed !== true && cache[key]?.pendingOperation === operation;
    }

    /**
     * 处理链路失败时撤销占位，让同一条未读消息可以在下一轮补扫中重试。
     */
    public removeMessage(bossId: number, text: string): void {
        const key = this.generateKey(bossId, text);
        const cache = this.getCache();
        if (!(key in cache)) {
            return;
        }
        delete cache[key];
        this.saveCache(cache);
    }

    /**
     * 生成唯一键
     * @param bossId - Boss ID
     * @param text - 消息内容
     * @returns 唯一键
     */
    private generateKey(bossId: number, text: string): string {
        return messageFingerprint(bossId, text);
    }
}

/**
 * 将有滚动条的元素滚动到底部
 * @param {string | Element | null | undefined} selector - CSS选择器字符串、DOM元素或不提供(自动查找)
 * @returns {boolean} - 是否成功滚动
 */
export function scrollElementToBottom(selector?: string | Element | null): number {
    try {
        // 确定目标元素
        let targetElement: Element | null = null;

        if (typeof selector === 'string') {
            // 如果提供的是CSS选择器
            targetElement = document.querySelector(selector);
        } else if (selector instanceof Element) {
            // 如果直接提供的是DOM元素
            targetElement = selector;
        } else {
            // 如果未提供选择器，尝试查找页面中第一个有滚动条的元素
            const scrollableElements: Element[] = Array.from(document.querySelectorAll('*')).filter(el => {
                const style = window.getComputedStyle(el);
                return (
                    (style.overflowY === 'scroll' || style.overflowY === 'auto') &&
                    el.scrollHeight > el.clientHeight
                );
            });

            if (scrollableElements.length > 0) {
                targetElement = scrollableElements[0];
                // console.log('自动检测到滚动元素:', targetElement);
            } else {
                // 如果找不到可滚动元素，回退到body
                targetElement = document.body;
            }
        }

        if (!targetElement) {
            console.error('未找到目标元素');
            return 0;
        }

        // 滚动到元素底部
        if ('scrollTop' in targetElement) {
            (targetElement as HTMLElement).scrollTop = targetElement.scrollHeight;
            // console.log(`已滚动元素到底部，总高度: ${targetElement.scrollHeight}px`);
            return targetElement.scrollHeight;
        } else {
            console.error('目标元素不支持滚动操作');
            return 0;
        }
    } catch (error) {
        console.error('滚动到底部失败:', error);
        return 0;
    }
}

// ---------------------------------------------------------------------------------------------------------------------

export enum Platform {
    WINDOWS = 'windows',
    MAC = 'mac'
}

export const simulateScrollToEnd = async (platform?: Platform) => {
    const isMac = platform === Platform.MAC || navigator.platform.toUpperCase().includes('MAC');
    const modifierKey = isMac ? 'Meta' : 'Control';
    const modifierSymbol = isMac ? '⌘' : 'Ctrl';

    // 方案1: 尝试通过真实键盘事件触发
    try {
        const activeElement = document.activeElement as HTMLElement;

        // 创建并触发组合键事件（增强版）
        const eventOptions = {
            key: 'End',
            code: 'End',
            [modifierKey.toLowerCase() + 'Key']: true,
            bubbles: true,
            cancelable: true,
            composed: true,
            view: window
        };

        // 同时触发keydown和keyup
        const downEvent = new KeyboardEvent('keydown', eventOptions);
        const upEvent = new KeyboardEvent('keyup', eventOptions);

        // 先尝试在document上触发（适用于全局快捷键）
        document.dispatchEvent(downEvent);
        document.dispatchEvent(upEvent);

        // 再尝试在焦点元素上触发（适用于编辑器等场景）
        if (activeElement) {
            activeElement.dispatchEvent(downEvent);
            activeElement.dispatchEvent(upEvent);
        }

        // 等待浏览器处理事件
        await new Promise(resolve => requestAnimationFrame(resolve));
    } catch (error) {
        console.warn('键盘事件触发失败，使用备选方案');
    }

    // 方案2: 直接操作滚动位置（备选方案）
    const getMaxScroll = () => {
        const documentElement = document.documentElement;
        return Math.max(
            document.body.scrollHeight,
            documentElement.scrollHeight,
            document.body.offsetHeight,
            documentElement.offsetHeight,
            document.body.clientHeight,
            documentElement.clientHeight
        ) - window.innerHeight;
    };

    // 平滑滚动到最底部（兼容模式）
    const maxScroll = getMaxScroll();
    if (window.scrollY !== maxScroll) {
        window.scrollTo({
            top: maxScroll,
            behavior: 'smooth'
        });
    }
};

// 使用示例
// simulateScrollToEnd(); // 自动检测系统
// simulateScrollToEnd(Platform.MAC); // 强制指定Mac模式
