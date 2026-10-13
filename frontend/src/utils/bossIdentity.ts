export type BossIdentity = {
    accountId: string;
    token: string;
}

type BossPageIdentity = {
    uid?: string | number | null;
    token?: string | null;
} | null | undefined;

export async function waitForBossIdentity(
    readPage: () => BossPageIdentity,
    expectedAccountId?: string,
    attempts = 240,
    intervalMs = 250
): Promise<BossIdentity> {
    const expected = String(expectedAccountId ?? "").trim();

    for (let attempt = 0; attempt < attempts; attempt += 1) {
        const page = readPage();
        const accountId = String(page?.uid ?? "").trim();
        const token = String(page?.token ?? "").trim();

        if (accountId && expected && accountId !== expected) {
            throw new Error("BOSS账号已切换，请刷新页面后重试");
        }
        if (accountId && token) {
            return {accountId, token};
        }
        if (attempt + 1 < attempts) {
            await new Promise<void>(resolve => setTimeout(resolve, intervalMs));
        }
    }

    throw new Error("未能读取 BOSS 登录身份，请确认已登录后刷新页面重试");
}
