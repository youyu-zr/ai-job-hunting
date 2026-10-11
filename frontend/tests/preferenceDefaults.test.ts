import assert from "node:assert/strict";
import test from "node:test";
import {
    applyPersonalPreferenceDefaults,
    PERSONAL_PREFERENCE_DEFAULTS,
    PERSONAL_PREFERENCE_DEFAULTS_VERSION,
} from "../src/stores/preferenceDefaults.ts";
import type {PreferenceConfig} from "../src/stores/types.ts";

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
    assert.equal(preference.pdpV, PERSONAL_PREFERENCE_DEFAULTS_VERSION);
});

test("does not refill a field intentionally cleared after initialization", () => {
    const preference = createPreference({jobGoal: "", pdpV: PERSONAL_PREFERENCE_DEFAULTS_VERSION});

    applyPersonalPreferenceDefaults(preference);

    assert.equal(preference.jobGoal, "");
});
