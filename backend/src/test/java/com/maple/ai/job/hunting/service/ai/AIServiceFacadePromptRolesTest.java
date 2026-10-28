package com.maple.ai.job.hunting.service.ai;

import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.config.AppBizConfig;
import com.maple.ai.job.hunting.emums.ProductTypeEnum;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.UserProductMapper;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.biz.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIServiceFacadePromptRolesTest {

    @AfterEach
    void clearHeader() {
        HeaderContext.clear();
    }

    @Test
    void userWithoutAnyProductRecordStillReachesTheModel() {
        AIService aiService = mock(AIService.class);
        when(aiService.askAndAnswer("SYSTEM rules", "USER data")).thenReturn("ok");
        AIServiceFacade facade = facade(aiService, false);

        assertEquals("ok", facade.askAndAnswer("SYSTEM rules", "USER data"));
    }

    @Test
    void preservesUnsupportedRoleSeparationFailure() {
        AIServiceFacade facade = facade(
                mock(AIService.class, CALLS_REAL_METHODS),
                true);

        ApplicationException error = assertThrows(ApplicationException.class,
                () -> facade.askAndAnswer("SYSTEM rules", "USER data"));

        assertEquals("当前 AI 服务不支持分离系统规则与用户内容", error.getMessage());
    }

    @Test
    void keepsExistingFallbackForUnrelatedApplicationFailures() {
        AIService aiService = mock(AIService.class);
        when(aiService.askAndAnswer(anyString(), anyString()))
                .thenThrow(new ApplicationException("provider details must not escape"));
        AIServiceFacade facade = facade(aiService, true);

        assertNull(facade.askAndAnswer("SYSTEM rules", "USER data"));
    }

    private AIServiceFacade facade(AIService aiService, boolean hasAbility) {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.setHeader(new Header(user, "request", "/api/job/filter/one", null, false, null, null));

        AppBizConfig appBizConfig = new AppBizConfig();
        ReflectionTestUtils.setField(appBizConfig, "sceneUseAiMap", Map.of("ask", "test"));
        UserProductMapper userProductMapper = mock(UserProductMapper.class);
        when(userProductMapper.queryUserValidAllProductType(42L)).thenReturn(hasAbility
                ? Set.of(ProductTypeEnum.AI_SEAT.getCode(), ProductTypeEnum.AI_FILTER.getCode())
                : Set.of());
        ReflectionTestUtils.setField(appBizConfig, "adminUserIdList", java.util.List.of());
        ProductService productService = new ProductService();
        ReflectionTestUtils.setField(productService, "userProductMapper", userProductMapper);
        ReflectionTestUtils.setField(productService, "appBizConfig", appBizConfig);

        AIServiceFacade facade = new AIServiceFacade();
        ReflectionTestUtils.setField(facade, "appBizConfig", appBizConfig);
        ReflectionTestUtils.setField(facade, "aiServiceMap", Map.of("test", aiService));
        ReflectionTestUtils.setField(facade, "productService", productService);
        return facade;
    }

}
