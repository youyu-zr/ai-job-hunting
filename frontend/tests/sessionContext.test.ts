import assert from "node:assert/strict";
import test from "node:test";

import {
    AUTHORIZATION_KEY,
    AUTHORIZATION_SCOPE_KEY,
    clearScopedAuthorization,
    isScopedAuthorizationCurrent,
    scopedAuthorization,
    storeScopedAuthorization,
    userSessionScope
} from "../src/utils/sessionContext.ts";

function createStorage() {
    const values = new Map<string, string>();
    return {
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => values.set(key, value),
        removeItem: (key: string) => values.delete(key)
    };
}

test("session scope normalizes account whitespace and server trailing slash", () => {
    assert.equal(
        userSessionScope(" boss-1 ", "https://one.example/"),
        userSessionScope("boss-1", "https://one.example")
    );
    assert.notEqual(
        userSessionScope("boss-1", "https://one.example"),
        userSessionScope("boss-1", "https://two.example")
    );
});

test("authorization is only readable by its matching account and server scope", () => {
    const storage = createStorage();
    const accountA = userSessionScope("account-a", "https://one.example");
    const accountB = userSessionScope("account-b", "https://one.example");
    assert.ok(accountA);
    assert.ok(accountB);

    storeScopedAuthorization("token-a", accountA!, storage);

    assert.equal(scopedAuthorization(accountA!, storage), "token-a");
    assert.equal(scopedAuthorization(accountB!, storage), null);
    assert.equal(isScopedAuthorizationCurrent(accountA!, "token-a", storage), true);
    assert.equal(isScopedAuthorizationCurrent(accountA!, "old-token", storage), false);
    assert.equal(isScopedAuthorizationCurrent(accountB!, "token-a", storage), false);
});

test("a stale unauthorized response cannot clear a newer token", () => {
    const storage = createStorage();
    const scope = userSessionScope("account-a", "https://one.example");
    assert.ok(scope);

    storeScopedAuthorization("old-token", scope!, storage);
    storeScopedAuthorization("new-token", scope!, storage);

    assert.equal(clearScopedAuthorization(scope!, "old-token", storage), false);
    assert.equal(scopedAuthorization(scope!, storage), "new-token");
    assert.equal(clearScopedAuthorization(scope!, "new-token", storage), true);
    assert.equal(storage.getItem(AUTHORIZATION_KEY), null);
    assert.equal(storage.getItem(AUTHORIZATION_SCOPE_KEY), null);
});

test("legacy unscoped authorization is not accepted", () => {
    const storage = createStorage();
    const scope = userSessionScope("account-a", "https://one.example");
    assert.ok(scope);
    storage.setItem(AUTHORIZATION_KEY, "legacy-token");

    assert.equal(scopedAuthorization(scope!, storage), null);
});
