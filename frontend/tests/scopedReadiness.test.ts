import assert from "node:assert/strict";
import test from "node:test";

import {createScopedReadiness} from "../src/stores/scopedReadiness.ts";

test("readiness from one account cannot satisfy another account's wait", async () => {
    const readiness = createScopedReadiness();
    readiness.begin("account-a");
    const oldAccountWait = readiness.wait("account-a", 1000);

    readiness.begin("account-b");
    assert.equal(await oldAccountWait, false);
    readiness.complete("account-a", true);
    assert.equal(readiness.isReady("account-a"), false);

    const currentAccountWait = readiness.wait("account-b", 1000);
    readiness.complete("account-b", true);
    assert.equal(await currentAccountWait, true);
    assert.equal(readiness.isReady("account-b"), true);
});

test("failed profile loading completes readiness as false", async () => {
    const readiness = createScopedReadiness();
    readiness.begin("account-a");
    const pending = readiness.wait("account-a", 1000);
    readiness.complete("account-a", false);

    assert.equal(await pending, false);
    assert.equal(readiness.isReady("account-a"), false);
});
