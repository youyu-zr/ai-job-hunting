import assert from "node:assert/strict";
import test from "node:test";
import {
    applyPersonalPreferenceDefaults,
    PERSONAL_PREFERENCE_DEFAULTS,
    PERSONAL_PREFERENCE_DEFAULTS_VERSION,
} from "../src/stores/preferenceDefaults.ts";
import type {PreferenceConfig} from "../src/stores/types.ts";
import {mergeRemoteUserConfig, captureUserConfigSnapshot} from "../src/stores/profileMerge.ts";

function createPreference(overrides: Partial<PreferenceConfig> = {}): PreferenceConfig {
    return {
        cniE: false,
        cni: [],
        cneE: false,
        cne: [],
        jniE: false,
        jni: [],
        jneE: false,
        jne: [],
        jceE: false,
        jce: [],
        jciE: false,
        jci: [],
        srE: false,
        sr: "",
        csrE: false,
        csr: "",
        fhE: false,
        polE: false,
        cgE: false,
        cg: "",
        afE: true,
        af: "",
        cIE: false,
        cI: "",
        hiaE: false,
        pi: 3,
        npi: 6,
        ...overrides,
    };
}

test("initializes common defaults while preserving a specific user preference", () => {
    const preference = createPreference({jobGoal: "只找杭州的 Java 后端岗位"});

    applyPersonalPreferenceDefaults(preference);

    assert.equal(preference.jobGoal, "只找杭州的 Java 后端岗位");
    assert.equal(preference.unacceptableConditions, PERSONAL_PREFERENCE_DEFAULTS.unacceptableConditions);
    assert.equal(preference.clarifyConditions, PERSONAL_PREFERENCE_DEFAULTS.clarifyConditions);
    assert.equal(preference.communicationPreferences, PERSONAL_PREFERENCE_DEFAULTS.communicationPreferences);
    assert.equal(preference.promotionReplyPolicy, PERSONAL_PREFERENCE_DEFAULTS.promotionReplyPolicy);
    assert.equal(preference.pdpV, PERSONAL_PREFERENCE_DEFAULTS_VERSION);
});

test("does not refill a field intentionally cleared after initialization", () => {
    const preference = createPreference({jobGoal: "", pdpV: PERSONAL_PREFERENCE_DEFAULTS_VERSION});

    applyPersonalPreferenceDefaults(preference);

    assert.equal(preference.jobGoal, "");
});

test("initializes generic defaults without inventing personal city salary or contact facts", () => {
    const preference = createPreference();

    applyPersonalPreferenceDefaults(preference);

    for (const value of Object.values(PERSONAL_PREFERENCE_DEFAULTS)) {
        assert.doesNotMatch(value, /上海|北京|杭州|深圳|商丘|[0-9]+\s?K|微信号|电话号/);
    }
});

test("upgrades known old defaults but preserves custom text and initialized blanks", () => {
    const preference = createPreference({
        pdpV: "personal-preferences-20261011-v1",
        jobGoal: "优先结合我的简历经历、技能和当前岗位职责判断匹配度；不要假设我未说明的行业、城市、薪资或岗位偏好。",
        unacceptableConditions: "  我的自定义条件  ",
        clarifyConditions: "",
        communicationPreferences: "保持礼貌、自然、简洁；只依据我的简历和已提供的信息回答，不编造经历；涉及电话或微信、面试安排、薪资、地点、到岗和合同承诺时，先由我本人确认。",
        promotionReplyPolicy: null as any,
    });

    applyPersonalPreferenceDefaults(preference);

    assert.equal(preference.jobGoal, PERSONAL_PREFERENCE_DEFAULTS.jobGoal);
    assert.equal(preference.unacceptableConditions, "  我的自定义条件  ");
    assert.equal(preference.clarifyConditions, "");
    assert.equal(preference.communicationPreferences, PERSONAL_PREFERENCE_DEFAULTS.communicationPreferences);
    assert.equal(preference.promotionReplyPolicy, PERSONAL_PREFERENCE_DEFAULTS.promotionReplyPolicy);
    assert.equal(preference.pdpV, PERSONAL_PREFERENCE_DEFAULTS_VERSION);
});

test("required labels and example questions stay in the form instead of saved defaults", async () => {
    const {readFile} = await import("node:fs/promises");
    const form = await readFile(new URL("../src/components/ui/Preference.vue", import.meta.url), "utf8");

    for (const label of [
        "我想找什么工作",
        "哪些情况我不能接受",
        "信息不全时优先问什么",
        "跟 HR 沟通时按什么方式",
        "我不希望 AI 回复哪些推广",
    ]) {
        assert.ok(form.includes(label), `missing preference label: ${label}`);
    }
    for (const example of ["最低薪资是多少", "优先哪个城市", "能否接受微信沟通"]) {
        assert.ok(form.includes(example), `missing help example: ${example}`);
        assert.ok(!JSON.stringify(PERSONAL_PREFERENCE_DEFAULTS).includes(example),
            `help example must not be persisted as a preference: ${example}`);
    }
});

test("late remote profile response does not overwrite preference edits made while loading", () => {
    const currentUser = {phone: "", email: "", preference: {jobGoal: "本地正在编辑"}};
    const baseline = captureUserConfigSnapshot({
        phone: currentUser.phone,
        email: currentUser.email,
        preference: {jobGoal: "请求开始时的值"},
    });

    const merged = mergeRemoteUserConfig(currentUser, {
        phone: "",
        email: "",
        preference: {jobGoal: "服务器返回的旧值"},
    }, baseline);

    assert.deepEqual(merged.preference, {jobGoal: "本地正在编辑"});
});
