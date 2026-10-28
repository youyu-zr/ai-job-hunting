/**
 * 会话自动恢复：服务器重启或会话闲置过期后，页面不用刷新，自动重新登录。
 *
 * 用「登记」方式拿到重登函数（由 stores/remote.ts 登记 userRemoteLoad），
 * 避免 axios.ts ↔ remote.ts 互相 import。
 */
type Relogin = () => Promise<void>;
type ReadyCheck = () => boolean;

const MIN_BACKOFF_MS = 30_000;
const MAX_BACKOFF_MS = 10 * 60_000;

let relogin: Relogin | null = null;
let isReady: ReadyCheck | null = null;
let inFlight: Promise<boolean> | null = null;
let failures = 0;
let nextAllowedAt = 0;

export function registerSessionRecovery(fn: Relogin, ready: ReadyCheck): void {
    relogin = fn;
    isReady = ready;
}

/**
 * 重新登录一次。多个调用同时进来只发一次；失败后按 30 秒起、最长 10 分钟退避，
 * 避免服务器挂掉时每 15 秒狂打登录接口。
 */
export function recoverSession(now = Date.now()): Promise<boolean> {
    if (!relogin || !isReady) {
        return Promise.resolve(false);
    }
    if (inFlight) {
        return inFlight;
    }
    if (now < nextAllowedAt) {
        return Promise.resolve(false);
    }
    const run = relogin;
    const ready = isReady;
    inFlight = run()
        .then(() => ready(), () => false)
        .then(ok => {
            if (ok) {
                failures = 0;
                nextAllowedAt = 0;
            } else {
                failures += 1;
                nextAllowedAt = Date.now() + Math.min(MIN_BACKOFF_MS * Math.pow(2, failures - 1), MAX_BACKOFF_MS);
            }
            return ok;
        })
        .finally(() => {
            inFlight = null;
        });
    return inFlight;
}

/** 重登流程本身会调用的接口：它们 401 时不能再等重登，否则会自己等自己。 */
const RECOVERY_OWN_URLS = ['/api/user/silently/login', '/api/user/userinfo', '/api/beta/access'];

export function canReplayAfterRelogin(config: any): boolean {
    if (!config || config.__retriedAfterRelogin || config.bossIdentityContext) {
        // 已重放过一次 / 简历导入（带身份上下文，可能已写入）都不自动重放。
        return false;
    }
    const url = String(config.url || '');
    return !RECOVERY_OWN_URLS.some(prefix => url.startsWith(prefix));
}

/** 仅测试用。 */
export function resetSessionRecoveryForTest(): void {
    relogin = null;
    isReady = null;
    inFlight = null;
    failures = 0;
    nextAllowedAt = 0;
}
