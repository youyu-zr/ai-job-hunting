export interface MessageMergeBatch<T> {
    key: string;
    texts: string[];
    items: T[];
}

interface PendingBatch<T> {
    texts: string[];
    textKeys: Set<string>;
    items: T[];
    promise: Promise<boolean>;
}

/**
 * Coalesces a short burst of messages from one contact into one AI turn.
 * The first message starts the window; later messages join without extending it.
 * Body-type partitions scope text deduplication, never the safety decision.
 */
export class MessageMergeQueue<T> {
    private readonly pending = new Map<string, PendingBatch<T>>();
    private readonly processing = new Map<string, Promise<boolean>>();
    private readonly windowMs: number;

    public constructor(windowMs = 3500) {
        this.windowMs = windowMs;
    }

    public enqueue(
        key: string,
        text: string,
        item: T,
        process: (batch: MessageMergeBatch<T>) => Promise<boolean> | boolean,
        textPartitionKey?: string,
    ): Promise<boolean> {
        const textKey = JSON.stringify([textPartitionKey, text]);
        const existing = this.pending.get(key);
        if (existing) {
            if (!existing.textKeys.has(textKey)) {
                existing.textKeys.add(textKey);
                existing.texts.push(text);
            }
            existing.items.push(item);
            return existing.promise;
        }

        const pending: PendingBatch<T> = {
            texts: [text],
            textKeys: new Set([textKey]),
            items: [item],
            promise: Promise.resolve(false),
        };
        pending.promise = new Promise<boolean>((resolve) => {
            setTimeout(() => {
                this.pending.delete(key);
                const batch = {
                    key,
                    texts: [...pending.texts],
                    items: [...pending.items],
                };
                const previous = this.processing.get(key) ?? Promise.resolve(false);
                const processing = previous.then(async () => {
                    try {
                        return await process(batch);
                    } catch {
                        return false;
                    }
                });
                this.processing.set(key, processing);
                void processing.then(result => {
                    if (this.processing.get(key) === processing) {
                        this.processing.delete(key);
                    }
                    resolve(result);
                });
            }, this.windowMs);
        });
        this.pending.set(key, pending);
        return pending.promise;
    }
}
