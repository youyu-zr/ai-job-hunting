/**
 * 会话暂停令牌（前端侧）。
 *
 * 服务端的人工暂停只在 AI 调用之前检查一次；模型生成期间用户手动介入时，
 * 已经生成的回复仍可能被发出去。这里给每个 jobKey 维护一个"生成代次"：
 * 用户暂停会话时递增代次，发送前再核对一次，代次变化就丢弃这条旧回复。
 * 该状态是会话级开关，刷新页面后按“未暂停”重新开始，真正的持久暂停仍由服务端负责。
 */
export class ConversationPauseRegistry {
    private static readonly generations = new Map<string, number>();
    private static readonly paused = new Set<string>();

    /** 开始一轮自动回复，返回本轮代次。 */
    public static beginTurn(jobKey: string): number {
        return this.generations.get(jobKey) ?? 0;
    }

    /** 人工暂停：递增代次，使生成中的旧回复失效。 */
    public static pause(jobKey: string): void {
        this.generations.set(jobKey, this.beginTurn(jobKey) + 1);
        this.paused.add(jobKey);
    }

    /** 人工恢复。 */
    public static resume(jobKey: string): void {
        this.generations.set(jobKey, this.beginTurn(jobKey) + 1);
        this.paused.delete(jobKey);
    }

    public static isPaused(jobKey: string): boolean {
        return this.paused.has(jobKey);
    }

    /**
     * 发送前校验：会话已被暂停，或本轮代次已被更新的暂停/恢复作废时返回 false。
     * 调用方必须据此丢弃这条回复，而不是把它补发出去。
     */
    public static canDeliver(jobKey: string, turnGeneration: number): boolean {
        if (this.paused.has(jobKey)) {
            return false;
        }
        return this.beginTurn(jobKey) === turnGeneration;
    }

    /** 仅供测试与账号切换使用。 */
    public static reset(): void {
        this.generations.clear();
        this.paused.clear();
    }
}