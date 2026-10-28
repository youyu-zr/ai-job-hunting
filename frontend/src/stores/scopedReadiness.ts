type ReadinessState = {
    scope: string;
    ready: boolean;
    completed: boolean;
    promise: Promise<boolean>;
    resolve: (ready: boolean) => void;
};

/**
 * Tracks whether the account's configuration finished loading.
 *
 * A completed *success* is terminal for the scope: asking again changes nothing. A completed
 * *failure* must not be, because recovering from that failure is the entire point of a retry.
 * Latching a failure permanently is what left the panel stuck at "not ready" until the user
 * reloaded the page by hand.
 */
export function createScopedReadiness() {
    let state: ReadinessState | null = null;

    function createState(scope: string): ReadinessState {
        let resolve!: (ready: boolean) => void;
        const promise = new Promise<boolean>((complete) => {
            resolve = complete;
        });
        return {scope, ready: false, completed: false, promise, resolve};
    }

    function ensure(scope: string): ReadinessState {
        if (!state || state.scope !== scope) {
            state?.resolve(false);
            state = createState(scope);
        }
        return state;
    }

    return {
        begin(scope: string): void {
            const current = state;
            if (current?.scope === scope) {
                if (!current.completed) {
                    return;
                }
                if (current.ready) {
                    // A success is the answer; a retry would only repeat it.
                    return;
                }
                // A settled failure is retryable: start a fresh attempt for the same scope.
                state = createState(scope);
                return;
            }
            current?.resolve(false);
            state = createState(scope);
        },

        complete(scope: string, ready: boolean): void {
            if (!state || state.scope !== scope) {
                return;
            }
            if (state.completed) {
                if (state.ready || !ready) {
                    // A success is the answer; a repeated failure changes nothing.
                    return;
                }
                // Two loads can share one attempt. If the one that finished first failed and the
                // other succeeded, the success is the truth: letting the earlier failure stand is
                // how a transient error used to leave the panel at "not loaded" for good.
                state.ready = true;
                state.resolve(true);
                return;
            }
            state.ready = ready;
            state.completed = true;
            state.resolve(ready);
        },

        isReady(scope: string): boolean {
            return Boolean(state && state.scope === scope && state.ready);
        },

        /** Drops a failed result so the next read is not answered by a stale negative. */
        invalidate(scope: string): void {
            if (!state || state.scope !== scope) {
                return;
            }
            if (state.completed && !state.ready) {
                state = null;
            }
        },

        wait(scope: string, timeoutMs: number): Promise<boolean> {
            const current = ensure(scope);
            if (current.completed) {
                return Promise.resolve(current.ready);
            }
            return new Promise<boolean>((resolve) => {
                const timer = setTimeout(() => resolve(false), timeoutMs);
                current.promise.then((ready) => {
                    clearTimeout(timer);
                    resolve(ready);
                });
            });
        },
    };
}
