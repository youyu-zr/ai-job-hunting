export type BossIdentity = {
    accountId: string;
    token: string;
}

type BossPageIdentity = {
    uid?: string | number | null;
    token?: string | null;
} | null | undefined;

type BossTokenReader = () => string | null | undefined;

type BossPageLoginState = {
    isLogin?: boolean | null;
} | null | undefined;

/**
 * BOSS reports its login state on the page before it exposes a uid. A page that says it is logged
 * out will never produce one, so polling to a minute-long deadline only makes the panel look
 * frozen. The wait is bounded well below a user's patience and fails immediately on that signal.
 */
const DEFAULT_ATTEMPTS = 32;
const DEFAULT_INTERVAL_MS = 250;

/**
 * A user who pressed "import resume" is waiting on that press, so a slow page must not fail them
 * out. Startup/reactive paths keep the short budget because retrying them is cheap and an empty
 * poll loop is what made the panel look frozen.
 */
export const INTERACTIVE_ATTEMPTS = 120;

/**
 * Do not trust a negative login flag on the very first look: BOSS renders the page object before it
 * hydrates it, and an early `isLogin: false` would otherwise fail a session that was about to work.
 */
const LOGOUT_SIGNAL_MIN_ATTEMPTS = 8;

export async function waitForBossIdentity(
    readPage: () => BossPageIdentity,
    expectedAccountId?: string,
    attempts = DEFAULT_ATTEMPTS,
    intervalMs = DEFAULT_INTERVAL_MS,
    readToken?: BossTokenReader,
    readLoginState?: () => BossPageLoginState
): Promise<BossIdentity> {
    const expected = String(expectedAccountId ?? "").trim();

    for (let attempt = 0; attempt < attempts; attempt += 1) {
        const page = readPage();
        const accountId = String(page?.uid ?? "").trim();
        const token = String(readToken ? readToken() ?? "" : page?.token ?? "").trim();

        if (accountId && expected && accountId !== expected) {
            throw new Error("BOSS账号已切换，请刷新页面后重试");
        }
        if (accountId && token) {
            return {accountId, token};
        }
        if (readLoginState
            && attempt >= LOGOUT_SIGNAL_MIN_ATTEMPTS
            && readLoginState()?.isLogin === false) {
            throw new Error("BOSS尚未登录，请先登录BOSS账号后刷新页面");
        }
        if (attempt + 1 < attempts) {
            await new Promise<void>(resolve => setTimeout(resolve, intervalMs));
        }
    }

    throw new Error("未能读取 BOSS 登录身份，请确认已登录后刷新页面重试");
}
