package com.maple.ai.job.hunting.utils;

import cn.hutool.json.JSONUtil;
import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.consts.AIPromptStrConstant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreferenceDefaultsTest {

    @Test
    void applyCleanDefaultsForEmptyPreference() {
        PreferenceEntity preference = PreferenceDefaults.applyDefaultRiskPack(new PreferenceEntity());

        assertTrue(preference.isAfE());
        assertFalse(preference.isJceE());
        assertEquals(PreferenceDefaults.DEFAULT_RISK_PACK_VERSION, preference.getDrpV());
        assertTrue(preference.getAf().contains("系统底层规则"));
        assertFalse(preference.getAf().contains("传销"));
        assertTrue(AIPromptStrConstant.AI_FILTER_SYSTEM_PROMPT.contains("拉人头"));
        assertTrue(preference.getJce() == null || preference.getJce().isEmpty());
    }

    @Test
    void upgradesOnlyTheKnownLegacyDefaultPrompt() {
        PreferenceEntity preference = new PreferenceEntity();
        preference.setAfE(true);
        preference.setAf("请结合完整职位信息和候选人求职方向判断岗位是否适合投递；信息不足但无明显风险时优先允许先沟通确认，不要仅因职位标题或单个关键词过滤。");
        preference.setDrpV("risk-pack-20260818-v1");

        PreferenceEntity result = PreferenceDefaults.applyDefaultRiskPack(preference);

        assertEquals(PreferenceDefaults.DEFAULT_AI_FILTER_PROMPT, result.getAf());
        assertEquals(PreferenceDefaults.DEFAULT_RISK_PACK_VERSION, result.getDrpV());
    }

    @Test
    void doNotReAddRiskPackAfterUserHasInitializedAndEdited() {
        PreferenceEntity preference = new PreferenceEntity();
        preference.setDrpV(PreferenceDefaults.DEFAULT_RISK_PACK_VERSION);
        preference.setJceE(false);
        preference.setJce(java.util.List.of());

        PreferenceEntity result = PreferenceDefaults.applyDefaultRiskPack(preference);

        assertFalse(result.isJceE());
        assertTrue(result.getJce().isEmpty());
    }

    @Test
    void preserveAiFilterPreferenceFields() {
        PreferenceEntity preference = JSONUtil.toBean(
                "{\"afE\":true,\"af\":\"先读取完整职位描述再判断\"}",
                PreferenceEntity.class);

        assertTrue(preference.isAfE());
        assertEquals("先读取完整职位描述再判断", preference.getAf());
    }

    @Test
    void personalRequirementsDoNotDisableCommonRiskDefaults() {
        PreferenceEntity preference = JSONUtil.toBean(
                "{\"jobGoal\":\"Java 后端\",\"communicationPreferences\":\"只接受线上沟通\"}",
                PreferenceEntity.class);

        PreferenceEntity result = PreferenceDefaults.applyDefaultRiskPack(preference);

        assertTrue(result.isAfE());
        assertEquals(PreferenceDefaults.DEFAULT_AI_FILTER_PROMPT, result.getAf());
        assertEquals(PreferenceDefaults.DEFAULT_RISK_PACK_VERSION, result.getDrpV());
        assertEquals("Java 后端", result.getJobGoal());
        assertEquals("只接受线上沟通", result.getCommunicationPreferences());
    }

    @Test
    void currentRiskPackKeepsDefaultPromptAfterPersonalFieldsAreAdded() {
        PreferenceEntity preference = JSONUtil.toBean(
                "{\"drpV\":\"risk-pack-20260829-v2\",\"afE\":true,\"jobGoal\":\"AI 应用开发\"}",
                PreferenceEntity.class);

        PreferenceEntity result = PreferenceDefaults.applyDefaultRiskPack(preference);

        assertEquals(PreferenceDefaults.DEFAULT_AI_FILTER_PROMPT, result.getAf());
        assertEquals("AI 应用开发", result.getJobGoal());
    }

    @Test
    void fillsGeneralPersonalDefaultsOnceWithoutOverwritingSpecificValues() {
        PreferenceEntity preference = new PreferenceEntity();
        preference.setJobGoal("只找杭州的 Java 后端岗位");

        PreferenceEntity result = PreferenceDefaults.applyDefaults(preference);

        assertEquals("只找杭州的 Java 后端岗位", result.getJobGoal());
        assertEquals(PreferenceDefaults.DEFAULT_UNACCEPTABLE_CONDITIONS, result.getUnacceptableConditions());
        assertEquals(PreferenceDefaults.DEFAULT_CLARIFY_CONDITIONS, result.getClarifyConditions());
        assertEquals(PreferenceDefaults.DEFAULT_COMMUNICATION_PREFERENCES, result.getCommunicationPreferences());
        assertEquals(PreferenceDefaults.PERSONAL_PREFERENCE_DEFAULTS_VERSION, result.getPdpV());
        assertTrue(result.isAfE());
    }

    @Test
    void preservesAnIntentionallyClearedDefaultAfterInitialization() {
        PreferenceEntity preference = new PreferenceEntity();
        preference.setPdpV(PreferenceDefaults.PERSONAL_PREFERENCE_DEFAULTS_VERSION);
        preference.setJobGoal("");

        PreferenceEntity result = PreferenceDefaults.applyDefaults(preference);

        assertEquals("", result.getJobGoal());
    }
}
