import assert from "node:assert/strict";
import test from "node:test";

import {waitForBossIdentity} from "../src/utils/bossIdentity.ts";

test("waits for the BOSS UID and login token before returning identity", async () => {
    let reads = 0;
    const identity = await waitForBossIdentity(() => {
        reads += 1;
        if (reads < 3) {
            return {uid: "", token: ""};
        }
        if (reads < 5) {
            return {uid: "  boss-1 ", token: ""};
        }
        return {uid: "boss-1", token: "live-token"};
    }, "", 6, 0);

    assert.deepEqual(identity, {accountId: "boss-1", token: "live-token"});
    assert.equal(reads, 5);
});

test("does not proceed with a different BOSS account than the expected account", async () => {
    await assert.rejects(
        waitForBossIdentity(() => ({uid: "boss-2", token: "live-token"}), "boss-1", 2, 0),
        /BOSS账号已切换/
    );
});

test("fails clearly when the user is not logged in and identity never appears", async () => {
    await assert.rejects(
        waitForBossIdentity(() => ({uid: "", token: ""}), "", 2, 0),
        /未能读取 BOSS 登录身份/
    );
});
