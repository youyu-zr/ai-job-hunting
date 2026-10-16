package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.common.ai.AIConfigHelper;
import com.maple.ai.job.hunting.common.ai.CompletionPathOpenAiApi;
import com.maple.ai.job.hunting.common.ai.ModelEndpointPolicy;
import com.maple.ai.job.hunting.emums.AIProviderEnum;
import com.maple.ai.job.hunting.emums.BizCodeEnum;
import com.maple.ai.job.hunting.emums.ProductTypeEnum;
import com.maple.ai.job.hunting.frame.cache.CustomOpenAiClientCache;
import com.maple.ai.job.hunting.frame.exp.AIPowerException;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.UserAIConfigMapper;
import com.maple.ai.job.hunting.model.bo.UserAIConfigDO;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 用户AI配置服务
 *
 * @author gaoping
 * @since 2025/04/11
 */
@Service
public class UserAIConfigService {

    @Resource
    private UserAIConfigMapper userAIConfigMapper;

    @Resource
    private CustomOpenAiClientCache customOpenAiClientCache;

    @Resource
    private AIConfigHelper aiConfigHelper;

    /**
     * 保存配置
     */
    public Boolean save(UserAIConfigDO config) {
        if (config == null || config.getUserId() == null) {
            throw new ApplicationException(BizCodeEnum.PARAM_ERROR);
        }

        UserAIConfigDO existConfig = userAIConfigMapper.selectByUserId(config.getUserId());
        if (existConfig == null) {
            prepareFirstConfig(config);
            customOpenAiClientCache.delete(config.getUserId());
            return userAIConfigMapper.insert(config) > 0;
        }

        config.setId(existConfig.getId());
        mergeWithExistingConfig(config, existConfig);
        customOpenAiClientCache.delete(config.getUserId());
        return userAIConfigMapper.updateById(config) > 0;
    }

    /**
     * 新用户可能先保存“用户提示词”，还没有填写自有 API。
     * 提示词属于 AI 坐席画像，不等价于启用自有 API；因此首次占位配置必须可入库，且默认禁用自有 API。
     */
    private void prepareFirstConfig(UserAIConfigDO config) {
        if (isPromptOnlySave(config)) {
            config.setStatus(0);
            config.setTestPassed(0);
        }
        if (config.getProvider() == null) {
            config.setProvider(AIProviderEnum.SILICON_FLOW.getCode());
        }
        if (config.getApiKey() == null) {
            config.setApiKey("");
        }
        if (config.getTimeout() == null) {
            config.setTimeout(60);
        }
        if (config.getTestPassed() == null) {
            config.setTestPassed(0);
        }
        if (config.getStatus() == null) {
            config.setStatus(0);
        }
    }

    /**
     * 局部保存提示词时，只更新本次提交的字段；已有 API 配置不能被空请求覆盖。
     */
    private void mergeWithExistingConfig(UserAIConfigDO config, UserAIConfigDO existConfig) {
        if (config.getProvider() == null) {
            config.setProvider(existConfig.getProvider());
        }
        if (config.getModelName() == null) {
            config.setModelName(existConfig.getModelName());
        }
        if (config.getApiKey() == null) {
            config.setApiKey(existConfig.getApiKey());
        }
        if (config.getBaseUrl() == null) {
            config.setBaseUrl(existConfig.getBaseUrl());
        }
        if (config.getTimeout() == null) {
            config.setTimeout(existConfig.getTimeout());
        }
        if (config.getCompletionsPath() == null) {
            config.setCompletionsPath(existConfig.getCompletionsPath());
        }
        if (config.getTestPassed() == null) {
            config.setTestPassed(existConfig.getTestPassed());
        }
        if (config.getStatus() == null) {
            config.setStatus(existConfig.getStatus());
        }
        if (config.getUserPrompt() == null) {
            config.setUserPrompt(existConfig.getUserPrompt());
        }
    }

    private boolean isPromptOnlySave(UserAIConfigDO config) {
        return config.getUserPrompt() != null
                && config.getProvider() == null
                && StringUtils.isBlank(config.getModelName())
                && config.getApiKey() == null
                && StringUtils.isBlank(config.getBaseUrl())
                && config.getTimeout() == null
                && StringUtils.isBlank(config.getCompletionsPath())
                && config.getTestPassed() == null
                && config.getStatus() == null;
    }

    /**
     * 获取当前用户配置
     */
    public UserAIConfigDO getCurrentConfig() {
        return userAIConfigMapper.selectByUserId(HeaderContext.getHeader().getUserId());
    }

    /**
     * 测试配置
     */
    public String testUserApiConfig(UserAIConfigDO config) {

        try {
            validateConfig(config, false);
            OpenAiChatClient client = buildOpenAiChatClient(config);

            // 发送测试请求
            String response = client.call("你好，你是");
            if (StringUtils.isBlank(response)) {
                throw new ApplicationException("响应为空");
            }

            return response;
        } catch (Exception e) {
            throw new ApplicationException("测试失败 " + e.getMessage(), BizCodeEnum.AI_CONFIG_TEST_FAILED.getCode());
        }
    }

    public boolean openCustomApi(Set<Integer> productPowerList) {
        if (!productPowerList.contains(ProductTypeEnum.CUSTOM_API.getCode())) {
            return false;
        }
        UserAIConfigDO currentConfig = userAIConfigMapper.getEnabledConfig(HeaderContext.getHeader().getUserId());
        if (currentConfig == null) {
            return false;
        }
        try {
            validateConfig(currentConfig, true);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 禁用配置
     */
    public Boolean disable(Long id) {
        UserAIConfigDO config = userAIConfigMapper.selectById(id);
        if (config == null) {
            throw new ApplicationException("配置不存在");
        }
        if (!config.getUserId().equals(HeaderContext.getHeader().getUserId())) {
            throw new ApplicationException("无权修改此配置");
        }

        config.setStatus(0);
        return userAIConfigMapper.updateById(config) > 0;
    }

    public void validateConfig(UserAIConfigDO config, boolean checkTestPassed) {
        if (config == null) {
            throw new AIPowerException("custom", "未配置AI服务");
        }

        if (StringUtils.isBlank(config.getApiKey())) {
            throw new AIPowerException("custom", "未配置API密钥");
        }
        if (checkTestPassed && config.getTestPassed() != 1) {
            throw new AIPowerException("custom", "配置未通过测试，请先测试配置");
        }

        if (config.getProvider() == null) {
            throw new AIPowerException("custom", "未配置API提供商");
        }

        if (StringUtils.isBlank(config.getModelName())) {
            throw new AIPowerException("custom", "未配置模型名称");
        }
        if (config.getTimeout() != null && (config.getTimeout() < 1 || config.getTimeout() > 120)) {
            throw new AIPowerException("custom", "模型请求超时时间必须在 1 到 120 秒之间");
        }
        ModelEndpointPolicy.validateCompletionPath(config.getCompletionsPath());

        AIProviderEnum provider = AIProviderEnum.getByCode(config.getProvider());
        if (provider == null) {
            throw new AIPowerException("custom", "无效的API提供商");
        }

        if (provider.isCustom() && StringUtils.isBlank(config.getBaseUrl())) {
            throw new AIPowerException("custom", "自定义API提供商必须配置baseUrl");
        }
    }

    public @NotNull OpenAiChatClient buildOpenAiChatClient(UserAIConfigDO config) {
        String baseUrl = config.getBaseUrl();

        // 根据大模型提供商，选择baseUrl
        if (StringUtils.isBlank(baseUrl)) {
            AIProviderEnum provider = AIProviderEnum.getByCode(config.getProvider());
            if (provider != null && provider.getDefaultBaseUrl() != null) {
                baseUrl = provider.getDefaultBaseUrl();
            }
        }
        aiConfigHelper.validateModelEndpoint(baseUrl);

        // 创建新的配置
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(config.getModelName())
                .build();

        /**
         * completionsPath 大模型提供商的baseUrl结尾都已经包含版本了。所以path要移除版本。
         * @see OpenAiApi#chatCompletionEntity(OpenAiApi.ChatCompletionRequest)
         */
        String completionsPath = StringUtils.defaultIfBlank(config.getCompletionsPath(), "/chat/completions");

        CompletionPathOpenAiApi openAiApi = new CompletionPathOpenAiApi(
                baseUrl,
                config.getApiKey(),
                completionsPath,
                aiConfigHelper.buildRestClient(config.getTimeout())
        );
        return new OpenAiChatClient(openAiApi, options);
    }

}
