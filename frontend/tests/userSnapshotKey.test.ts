import assert from "node:assert/strict";
import test from "node:test";

import {userSnapshotStorageKey} from "../src/stores/userSnapshotKey.ts";

test("user snapshots are isolated by BOSS account id", () => {
    assert.notEqual(
        userSnapshotStorageKey("account-a", "https://one.example/"),
        userSnapshotStorageKey("account-b", "https://one.example/")
    );
});

test("user snapshots are isolated by server as well as BOSS account", () => {
    assert.notEqual(
        userSnapshotStorageKey("account-a", "https://one.example/"),
        userSnapshotStorageKey("account-a", "https://two.example/")
    );
});

test("no shared snapshot key is created when the account id is unavailable", () => {
    assert.equal(userSnapshotStorageKey("", "https://one.example/"), null);
    assert.equal(userSnapshotStorageKey(null, "https://one.example/"), null);
    assert.equal(userSnapshotStorageKey(undefined, "https://one.example/"), null);
});
