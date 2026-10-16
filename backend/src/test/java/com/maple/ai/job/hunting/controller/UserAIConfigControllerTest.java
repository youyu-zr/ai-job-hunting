package com.maple.ai.job.hunting.controller;

import cn.hutool.json.JSONUtil;
import com.maple.ai.job.hunting.model.bo.UserAIConfigDO;
import com.maple.ai.job.hunting.model.vo.UserAIConfigVO;
import com.maple.ai.job.hunting.service.biz.UserAIConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAIConfigControllerTest {

    @Test
    void currentConfigReportsKeyPresenceWithoutReturningTheSecret() {
        String secret = "test-secret-that-must-not-leave-the-server";
        UserAIConfigDO config = new UserAIConfigDO();
        config.setApiKey(secret);
        UserAIConfigService service = new UserAIConfigService() {
            @Override
            public UserAIConfigDO getCurrentConfig() {
                return config;
            }
        };
        UserAIConfigController controller = new UserAIConfigController();
        ReflectionTestUtils.setField(controller, "userAIConfigService", service);

        String response = JSONUtil.toJsonStr(controller.getCurrentConfig());

        assertTrue(response.contains("\"apiKeyConfigured\":true"));
        assertFalse(response.contains(secret));
    }

    @Test
    void savingAnEmptyKeyPreservesItUnlessClearWasExplicitlyRequested() {
        UserAIConfigDO[] saved = new UserAIConfigDO[1];
        UserAIConfigService service = new UserAIConfigService() {
            @Override
            public Boolean save(UserAIConfigDO config) {
                saved[0] = config;
                return true;
            }
        };
        UserAIConfigController controller = new UserAIConfigController();
        ReflectionTestUtils.setField(controller, "userAIConfigService", service);
        UserAIConfigVO config = new UserAIConfigVO();
        config.setApiKey("");

        controller.save(config);

        assertNull(saved[0].getApiKey());

        UserAIConfigVO clearConfig = new UserAIConfigVO();
        clearConfig.setApiKey("");
        clearConfig.setClearApiKey(true);
        controller.save(clearConfig);

        assertEquals("", saved[0].getApiKey());
    }
}
