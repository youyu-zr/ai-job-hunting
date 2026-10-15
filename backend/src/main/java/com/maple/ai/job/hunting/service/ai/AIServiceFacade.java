package com.maple.ai.job.hunting.service.ai;

import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.config.AppBizConfig;
import com.maple.ai.job.hunting.emums.JobSeekerClonedAnswerTypeEnum;
import com.maple.ai.job.hunting.emums.ProductTypeEnum;
import com.maple.ai.job.hunting.frame.exp.AIPowerException;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.model.AiFileResolveResult;
import com.maple.ai.job.hunting.model.ChatSessionResult;
import com.maple.ai.job.hunting.model.entity.AlarmThresholdConfig;
import com.maple.ai.job.hunting.model.entity.SendEmailEntity;
import com.maple.ai.job.hunting.service.biz.EmailService;
import com.maple.ai.job.hunting.service.biz.ProductService;
import com.maple.smart.config.core.annotation.JsonValue;
import com.maple.ai.job.hunting.service.biz.SlidingWindowAlarmService;
import com.maple.ai.job.hunting.utils.LogValueSanitizer;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * @author maple
 * Created Date: 2024/4/27 23:21
 * Description:
 */

@Slf4j
@Service
public class AIServiceFacade {

    @Resource
    private AppBizConfig appBizConfig;

    @Resource
    private Map<String, AIService> aiServiceMap;

    @Resource
    private EmailService emailService;

    @Resource
    private SlidingWindowAlarmService slidingWindowAlarmService;

    @Resource
    private ProductService productService;

    @JsonValue("${ai.alarm.threshold.map:{\"def\":{\"count\":3,\"minutes\":1}}}")
    private Map<String, AlarmThresholdConfig> aiAlarmThresholdMap;

    @Value("${ai.error.email:true}")
    private Boolean aiErrorEmail;

    @Value("${ai.error.user.email:true}")
    private Boolean aiErrorUserEmail;

    @Nonnull
    public ChatSessionResult sessionChat(String sessionId, String ask) {
        if (sessionId != null && sessionId.contains("ask-debug-")) {
            Map<String, Object> extMap = Optional.ofNullable(HeaderContext.getHeader().getExtend()).orElse(new HashMap<>());
            extMap.put("debug", Boolean.TRUE);
            HeaderContext.getHeader().setExtend(extMap);
            return debugSessionChat(sessionId, ask);
        }
        AIService aiService = aiServiceMap.get(appBizConfig.getSceneUseAiMap().getOrDefault("session", "openai-pool"));
        ChatSessionResult chatSessionResult = new ChatSessionResult();
        try {
            if (productService.hasProductAbility(HeaderContext.getHeader().getUserId(), ProductTypeEnum.AI_SEAT.getCode())) {
                chatSessionResult = aiService.sessionChat(sessionId, ask);
            } else {
                throw new ApplicationException("AI坐席已过期");
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("AI坐席已过期")) {
                throw e;
            }
            if (e instanceof ApplicationException && "请先导入简历".equals(e.getMessage())) {
                throw e;
            }
            aiPowerExpAlarm(e);
            log.error("sessionChat error sessionId:{} errorType:{} error:{}",
                    sessionId, e.getClass().getSimpleName(), LogValueSanitizer.sanitize(e.getMessage(), 200));
            chatSessionResult.setAnswerStatus(JobSeekerClonedAnswerTypeEnum.AI_SERVICE_EXCEPTION);
            return chatSessionResult;
        }
        return chatSessionResult;
    }


    @Nonnull
    public ChatSessionResult debugSessionChat(String sessionId, String ask) {
        AIService aiService = aiServiceMap.get(appBizConfig.getSceneUseAiMap().getOrDefault("session", "openai-pool"));
        ChatSessionResult chatSessionResult = new ChatSessionResult();
        try {
            if (productService.hasProductAbility(HeaderContext.getHeader().getUserId(), ProductTypeEnum.AI_SEAT.getCode())) {
                chatSessionResult = aiService.sessionChat(sessionId, ask);
            } else {
                throw new ApplicationException("AI坐席已过期");
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("AI坐席已过期")) {
                throw e;
            }
            log.error("debugSessionChat error sessionId:{} errorType:{} error:{}",
                    sessionId, e.getClass().getSimpleName(), LogValueSanitizer.sanitize(e.getMessage(), 200));
            chatSessionResult.setAnswerStatus(JobSeekerClonedAnswerTypeEnum.AI_SERVICE_EXCEPTION);
            return chatSessionResult;
        }
        return chatSessionResult;
    }


    public String askAndAnswer(String ask) {
        AIService aiService = aiServiceMap.get(appBizConfig.getSceneUseAiMap().get("ask"));
        String reuslt = null;
        try {
            if (productService.hasProductAbilityAll(HeaderContext.getHeader().getUserId(), ProductTypeEnum.AI_SEAT, ProductTypeEnum.AI_FILTER)) {
                reuslt = aiService.askAndAnswer(ask);
            } else {
                throw new ApplicationException("AI产品能力已过期");
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("AI产品能力已过期")) {
                throw e;
            }
            aiPowerExpAlarm(e);
            log.error("askAndAnswer error userId:{} errorType:{} error:{}",
                    HeaderContext.getHeader().getUserId(), e.getClass().getSimpleName(),
                    LogValueSanitizer.sanitize(e.getMessage(), 200));
        }
        return reuslt;
    }

    public String askAndAnswer(String systemPrompt, String userPrompt) {
        AIService aiService = aiServiceMap.get(appBizConfig.getSceneUseAiMap().get("ask"));
        String result = null;
        try {
            if (productService.hasProductAbilityAll(HeaderContext.getHeader().getUserId(),
                    ProductTypeEnum.AI_SEAT, ProductTypeEnum.AI_FILTER)) {
                result = aiService.askAndAnswer(systemPrompt, userPrompt);
            } else {
                throw new ApplicationException("AI产品能力已过期");
            }
        } catch (Exception e) {
            if ((e.getMessage() != null && e.getMessage().contains("AI产品能力已过期"))
                    || (e instanceof ApplicationException
                    && "当前 AI 服务不支持分离系统规则与用户内容".equals(e.getMessage()))) {
                throw e;
            }
            aiPowerExpAlarm(e);
            log.error("askAndAnswer error userId:{} errorType:{} error:{}",
                    HeaderContext.getHeader().getUserId(), e.getClass().getSimpleName(),
                    LogValueSanitizer.sanitize(e.getMessage(), 200));
        }
        return result;
    }


    public AiFileResolveResult readFile(InputStream inputStream, String ask) {
        AIService aiService = aiServiceMap.get(appBizConfig.getSceneUseAiMap().getOrDefault("file", "silicon-flow-file"));
        return aiService.readFile(inputStream, ask);
    }

    /**
     * AI Power EXP 告警
     *
     * @param e e
     */
    private void aiPowerExpAlarm(Exception e) {
        if (e instanceof AIPowerException aiPowerException) {
            Long userId = HeaderContext.getHeader().getUserId();
            String exceptionName = aiPowerException.getName();
            String key = aiPowerException.getKey().endsWith("-")
                    ? aiPowerException.getKey() + userId
                    : aiPowerException.getKey();
            log.error("aiPower error {}", key);

            // Get alarm configuration
            AlarmThresholdConfig thresholdConfig = getAlarmThreshold(aiPowerException);
            Integer thresholdCount = thresholdConfig.getCount();
            Integer thresholdMinutes = thresholdConfig.getMinutes();

            int currentCountInWindow = slidingWindowAlarmService.recordAndGetCount(exceptionName, thresholdMinutes);

            // System-level alarm, triggers only when the threshold is first crossed
            if (currentCountInWindow >= thresholdCount && aiErrorEmail) {
                emailService.sendEmail(SendEmailEntity.builder()
                        .subject("AI服务异常通知")
                        .templateName("ai-error-email.html")
                        .toSendUserSet(new HashSet<>(appBizConfig.getAdminUserIdList()))
                        .paramMap(Map.of("count", currentCountInWindow, "msg",
                                LogValueSanitizer.sanitize(e.getMessage(), 200), "name", key))
                        .build());
            }

            // User-facing alarm for their custom API key, may trigger repeatedly
            if (aiErrorEmail && aiErrorUserEmail && key.contains("unknown") && currentCountInWindow >= thresholdCount) {
                emailService.sendEmail(SendEmailEntity.builder()
                        .subject("AI工作猎手")
                        .templateName("custom-ai-error-email.html")
                        .toSendUserSet(Set.of(userId))
                        .paramMap(Map.of("count", currentCountInWindow, "msg",
                                LogValueSanitizer.sanitize(e.getMessage(), 200), "name", key))
                        .build());
            }
        }
    }

    private AlarmThresholdConfig getAlarmThreshold(AIPowerException aiPowerException) {
        AlarmThresholdConfig defConfig = aiAlarmThresholdMap.get("def");
        // Failsafe in case the default config from JSON is not loaded
        if (defConfig == null) {
            defConfig = new AlarmThresholdConfig();
            defConfig.setCount(3);
            defConfig.setMinutes(1); // Correct failsafe
        }

        AlarmThresholdConfig specificConfig = aiAlarmThresholdMap.get(aiPowerException.getName());

        // If no specific config, return the default
        if (specificConfig == null) {
            return defConfig;
        }

        // If specific config exists, merge it with default
        // If specific value is null, use default value
        if (specificConfig.getCount() == null) {
            specificConfig.setCount(defConfig.getCount());
        }
        if (specificConfig.getMinutes() == null) {
            specificConfig.setMinutes(defConfig.getMinutes());
        }

        return specificConfig;
    }

}
