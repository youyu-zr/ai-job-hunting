export const AUTHORIZATION_KEY = "Authorization";
export const AUTHORIZATION_SCOPE_KEY = "AuthorizationScope";

type StorageLike = Pick<Storage, "getItem" | "setItem" | "removeItem">;

function normalizedServer(serverUrl: string | null | undefined): string | null {
    const raw = String(serverUrl ?? "").trim();
    if (!raw) {
        return null;
    }
    try {
        const parsed = new URL(raw);
        if (!["http:", "https:"].includes(parsed.protocol) || parsed.username || parsed.password) {
            return null;
        }
        parsed.hash = "";
        return `${parsed.origin}${parsed.pathname.replace(/\/+$/, "")}${parsed.search}`;
    } catch {
        return null;
    }
}

function hash(value: string, seed: number): string {
    let result = seed;
    for (let index = 0; index < value.length; index += 1) {
        result = Math.imul(result ^ value.charCodeAt(index), 16777619);
    }
    return (result >>> 0).toString(16).padStart(8, "0");
}

export function userSessionScope(
    accountId: string | number | null | undefined,
    serverUrl: string | null | undefined
): string | null {
    const normalizedAccountId = String(accountId ?? "").trim();
    const server = normalizedServer(serverUrl);
    if (!normalizedAccountId || !server) {
        return null;
    }
    const identity = `${server}\u0000${normalizedAccountId}`;
    return `v1-${hash(identity, 2166136261)}${hash(identity, 0x9e3779b9)}`;
}

export function scopedAuthorization(
    scope: string | null,
    storage: StorageLike = localStorage
): string | null {
    if (!scope || storage.getItem(AUTHORIZATION_SCOPE_KEY) !== scope) {
        return null;
    }
    return storage.getItem(AUTHORIZATION_KEY);
}

export function isScopedAuthorizationCurrent(
    scope: string | null,
    token: string | null | undefined,
    storage: StorageLike = localStorage
): boolean {
    return Boolean(token && scopedAuthorization(scope, storage) === token);
}

export function storeScopedAuthorization(
    token: string,
    scope: string,
    storage: StorageLike = localStorage
): void {
    storage.setItem(AUTHORIZATION_SCOPE_KEY, scope);
    storage.setItem(AUTHORIZATION_KEY, token);
}

export function clearScopedAuthorization(
    scope: string | null,
    expectedToken?: string,
    storage: StorageLike = localStorage
): boolean {
    if (!scope || storage.getItem(AUTHORIZATION_SCOPE_KEY) !== scope) {
        return false;
    }
    if (expectedToken !== undefined && storage.getItem(AUTHORIZATION_KEY) !== expectedToken) {
        return false;
    }
    clearAuthorization(storage);
    return true;
}

export function clearAuthorization(storage: StorageLike = localStorage): void {
    storage.removeItem(AUTHORIZATION_KEY);
    storage.removeItem(AUTHORIZATION_SCOPE_KEY);
}
