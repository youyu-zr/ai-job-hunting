export interface MessageMergeBatch<T> {
    key: string;
    texts: string[];
    items: T[];
}

interface PendingBatch<T> {
    texts: string[];
    items: T[];
    promise: Promise<boolean>;
}

/**
 * Coalesces a short burst of messages from one contact into one AI turn.
 * The first message starts the window; later messages join without extending it.
 */
export class MessageMergeQueue<T> {
    private readonly pending = new Map<string, PendingBatch<T>>();
    private readonly windowMs: number;

    public constructor(windowMs = 3500) {
        this.windowMs = windowMs;
    }

    public enqueue(
        key: string,
        text: string,
        item: T,
        process: (batch: MessageMergeBatch<T>) => Promise<boolean> | boolean,
        partitionKey?: string,
    ): Promise<boolean> {
        const pendingKey = partitionKey === undefined ? key : `${key}\0${partitionKey}`;
        const existing = this.pending.get(pendingKey);
        if (existing) {
            if (!existing.texts.includes(text)) {
                existing.texts.push(text);
                existing.items.push(item);
            }
            return existing.promise;
        }

        const pending: PendingBatch<T> = {
            texts: [text],
            items: [item],
            promise: Promise.resolve(false),
        };
        pending.promise = new Promise<boolean>((resolve) => {
            setTimeout(async () => {
                this.pending.delete(pendingKey);
                try {
                    resolve(await process({
                        key,
                        texts: [...pending.texts],
                        items: [...pending.items],
                    }));
                } catch {
                    resolve(false);
                }
            }, this.windowMs);
        });
        this.pending.set(pendingKey, pending);
        return pending.promise;
    }
}
