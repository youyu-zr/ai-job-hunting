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

test("server-filled wechat survives local unsaved preference edits", () => {
    const baseline = captureUserConfigSnapshot({
        phone: "", email: "", resumeId: "",
        preference: {jobGoal: "前端工程师"}
    });
    const current = {
        phone: "", email: "", resumeId: "",
        preference: {jobGoal: "后端工程师"}
    };

    const merged = mergeRemoteUserConfig(current, {
        phone: "", email: "", resumeId: "resume-1",
        preference: {jobGoal: "前端工程师", wechatId: "zr_2026abc"}
    }, baseline);

    assert.deepEqual(merged.preference, {jobGoal: "后端工程师", wechatId: "zr_2026abc"});
});

test("local wechat is not replaced by the server value", () => {
    const baseline = captureUserConfigSnapshot({
        phone: "", email: "", resumeId: "",
        preference: {wechatId: ""}
    });
    const current = {
        phone: "", email: "", resumeId: "",
        preference: {wechatId: "typed_by_me"}
    };

    const merged = mergeRemoteUserConfig(current, {
        phone: "", email: "", resumeId: "",
        preference: {wechatId: "from_resume"}
    }, baseline);

    assert.equal(merged.preference.wechatId, "typed_by_me");
});

test("wechat the user just cleared is not refilled from the server", () => {
    const baseline = captureUserConfigSnapshot({
        phone: "", email: "", resumeId: "",
        preference: {wechatId: "old_wx_01"}
    });
    const current = {
        phone: "", email: "", resumeId: "",
        preference: {wechatId: ""}
    };

    const merged = mergeRemoteUserConfig(current, {
        phone: "", email: "", resumeId: "",
        preference: {wechatId: "old_wx_01"}
    }, baseline);

    assert.equal((merged.preference as any).wechatId, "");
});
