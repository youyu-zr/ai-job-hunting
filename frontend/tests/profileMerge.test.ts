import assert from "node:assert/strict";
import test from "node:test";

import {
    captureUserConfigSnapshot,
    mergeRemoteUserConfig
} from "../src/stores/profileMerge.ts";

test("late server profile response preserves fields edited after the request began", () => {
    const baseline = captureUserConfigSnapshot({
        phone: "",
        email: "",
        resumeId: "",
        preference: {jobGoal: "前端工程师"}
    });
    const current = {
        phone: "13800000000",
        email: "",
        resumeId: "",
        preference: {jobGoal: "后端工程师"}
    };

    const merged = mergeRemoteUserConfig(current, {
        phone: "",
        email: "candidate@example.com",
        resumeId: "resume-1",
        preference: {jobGoal: "前端工程师"},
        inviteCode: "server-invite"
    }, baseline);

    assert.equal(merged.phone, "13800000000");
    assert.equal(merged.email, "candidate@example.com");
    assert.equal(merged.resumeId, "resume-1");
    assert.deepEqual(merged.preference, {jobGoal: "后端工程师"});
    assert.equal(merged.inviteCode, "server-invite");
});

test("unchanged local fields accept the server profile", () => {
    const current = {
        phone: "",
        email: "",
        resumeId: "",
        preference: {jobGoal: "前端工程师"}
    };
    const baseline = captureUserConfigSnapshot(current);

    const merged = mergeRemoteUserConfig(current, {
        phone: "13800000000",
        email: "candidate@example.com",
        resumeId: "resume-1",
        preference: {jobGoal: "后端工程师"}
    }, baseline);

    assert.equal(merged.phone, "13800000000");
    assert.equal(merged.email, "candidate@example.com");
    assert.equal(merged.resumeId, "resume-1");
    assert.deepEqual(merged.preference, {jobGoal: "后端工程师"});
});
