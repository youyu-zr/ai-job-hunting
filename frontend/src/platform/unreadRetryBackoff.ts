/**
 * 未读补扫的失败退避：同一联系人的同一条消息连续失败时，逐次拉长重试间隔。
 *
 * 15s → 30s → 1min → 2min → 4min → 8min → 10min（封顶）；连续 8 次失败后歇 1 小时再试，
 * 避免一条一直失败的消息每 15 秒打一次服务器、刷一次 AI。成功后清零。
 */
const BASE_MS = 15_000;
const MAX_MS = 10 * 60_000;
const GIVE_UP_AFTER = 8;
const GIVE_UP_PAUSE_MS = 60 * 60_000;

type Entry = { failures: number; nextAt: number };

export class UnreadRetryBackoff {
    private readonly entries = new Map<string, Entry>();

    private static key(bossId: string | number, text: string): string {
        return String(bossId) + '\u0000' + text;
    }

    isDue(bossId: string | number, text: string, now = Date.now()): boolean {
        const entry = this.entries.get(UnreadRetryBackoff.key(bossId, text));
        return !entry || now >= entry.nextAt;
    }

    /** 记一次失败，返回下次最早可重试的等待毫秒数。 */
    recordFailure(bossId: string | number, text: string, now = Date.now()): number {
        const key = UnreadRetryBackoff.key(bossId, text);
        const failures = (this.entries.get(key)?.failures ?? 0) + 1;
        const wait = failures >= GIVE_UP_AFTER
            ? GIVE_UP_PAUSE_MS
            : Math.min(BASE_MS * Math.pow(2, failures - 1), MAX_MS);
        this.entries.set(key, {failures: failures >= GIVE_UP_AFTER ? 0 : failures, nextAt: now + wait});
        if (this.entries.size > 500) {
            // 防止长时间挂机时无限增长：丢掉最早的一条。
            const oldest = this.entries.keys().next().value;
            if (oldest !== undefined) {
                this.entries.delete(oldest);
            }
        }
        return wait;
    }

    clear(bossId: string | number, text: string): void {
        this.entries.delete(UnreadRetryBackoff.key(bossId, text));
    }
}
