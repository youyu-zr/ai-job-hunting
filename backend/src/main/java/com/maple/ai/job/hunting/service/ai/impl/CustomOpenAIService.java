package com.maple.ai.job.hunting.service.ai.impl;

import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.frame.cache.CustomOpenAiClientCache;
import com.maple.ai.job.hunting.mapper.UserAIConfigMapper;
import com.maple.ai.job.hunting.model.bo.UserAIConfigDO;
import com.maple.ai.job.hunting.service.biz.UserAIConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.stereotype.Service;

/**
 * 自定义OpenAI服务实现
 *
 * @author gaoping
 * @since 2025/04/11
 */
@Slf4j
@Service("customOpenAI")
public class CustomOpenAIService extends OpenAIService {

    @Resource
    private CustomOpenAiClientCache clientCache;

    @Resource
    private UserAIConfigMapper userAIConfigMapper;

    @Resource
    private UserAIConfigService userAIConfigService;

    @Override
    protected OpenAiChatClient getClient() {
        Long userId = HeaderContext.getHeader().getUserId();
        OpenAiChatClient client = clientCache.get(userId);

        // 构建client缓存
        if (client == null) {
            UserAIConfigDO config = getUserConfig();
            userAIConfigService.validateConfig(config, true);
            client = userAIConfigService.buildOpenAiChatClient(config);
            clientCache.set(userId, client);
        }
        return client;
    }


    private UserAIConfigDO getUserConfig() {
        Long userId = HeaderContext.getHeader().getUserId();
        return userAIConfigMapper.getEnabledConfig(userId);
    }


}
