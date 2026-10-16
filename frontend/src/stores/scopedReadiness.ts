type ReadinessState = {
    scope: string;
    ready: boolean;
    completed: boolean;
    promise: Promise<boolean>;
    resolve: (ready: boolean) => void;
};

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
            if (current?.scope === scope && !current.completed) {
                return;
            }
            current?.resolve(false);
            state = createState(scope);
        },

        complete(scope: string, ready: boolean): void {
            if (!state || state.scope !== scope || state.completed) {
                return;
            }
            state.ready = ready;
            state.completed = true;
            state.resolve(ready);
        },

        isReady(scope: string): boolean {
            return Boolean(state && state.scope === scope && state.ready);
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
