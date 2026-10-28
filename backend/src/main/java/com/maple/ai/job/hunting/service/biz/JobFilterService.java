package com.maple.ai.job.hunting.service.biz;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.consts.AIPromptStrConstant;
import com.maple.ai.job.hunting.emums.BizCodeEnum;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.model.param.AIFilterParam;
import com.maple.ai.job.hunting.model.vo.AiFilterResultVO;
import com.maple.ai.job.hunting.service.ai.AIServiceFacade;
import com.maple.ai.job.hunting.utils.PromptDataSanitizer;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Job Filter 服务
 *
 * @author gaoping
 * @since 2025/06/14
 */
@Service
public class JobFilterService {

    private static final Logger log = LoggerFactory.getLogger(JobFilterService.class);
    private static final ObjectMapper SAFE_JSON = new ObjectMapper();
    private static final ObjectReader DECISION_JSON = SAFE_JSON.reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final java.util.regex.Pattern WHOLE_CODE_FENCE = java.util.regex.Pattern.compile(
            "^\\s*```(?:json|JSON)?\\s*\\n([\\s\\S]*?)\\n?\\s*```\\s*$");
    private static final String TEMPORARY_FAILURE_MESSAGE = "岗位分析暂时不可用，请稍后重试";
    @Resource
    private AIServiceFacade aiServiceFacade;
    @Resource
    private UserResumeMapper userResumeMapper;
    @Resource
    private UserInfoMapper userInfoMapper;

    public AiFilterResultVO filter(AIFilterParam param) {
        AIFilterParam enrichedParam;
        String userPrompt;
        try {
            enrichedParam = enrichWithUserContext(param);
            userPrompt = buildQuestion(enrichedParam);
        } catch (Exception e) {
            log.warn("AI岗位分析资料准备失败");
            throw temporaryFailure();
        }
        String answer;
        try {
            answer = aiServiceFacade.askAndAnswer(
                    AIPromptStrConstant.AI_FILTER_SYSTEM_PROMPT,
                    userPrompt);
        } catch (ApplicationException e) {
            if (isNonRetryableAiServiceFailure(e)) {
                throw e;
            }
            log.warn("AI岗位分析调用失败");
            throw temporaryFailure();
        } catch (Exception e) {
            log.warn("AI岗位分析调用失败");
            throw temporaryFailure();
        }
        if (StringUtils.isBlank(answer)) {
            log.warn("AI岗位分析结果为空");
            throw temporaryFailure();
        }

        try {
            JsonNode rawResult = DECISION_JSON.readTree(stripWholeCodeFence(answer));
            if (!hasCompleteDecisionFields(rawResult)) {
                throw temporaryFailure();
            }
            AiFilterResultVO result = normalizeStructuredResult(
                    SAFE_JSON.treeToValue(rawResult, AiFilterResultVO.class));
            logStructuredResult(result);
            return result;
        } catch (ApplicationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI岗位分析结果解析失败，响应长度:{}", answer.length());
            throw temporaryFailure();
        }
    }

    private boolean hasCompleteDecisionFields(JsonNode result) {
        if (result == null || !result.isObject() || !result.path("filter").isBoolean()) {
            return false;
        }
        String action = StringUtils.upperCase(StringUtils.trimToEmpty(result.path("recommendedAction").asText()));
        String risk = StringUtils.upperCase(StringUtils.trimToEmpty(result.path("riskLevel").asText()));
        if (!List.of("DELIVER", "CHAT_FIRST", "FILTER").contains(action)
                || !List.of("LOW", "MEDIUM", "HIGH").contains(risk)
                || StringUtils.isBlank(result.path("reason").asText())) {
            return false;
        }
        if ("CHAT_FIRST".equals(action)) {
            return true;
        }
        return result.path("riskSignals").isArray()
                && result.path("clarificationQuestions").isArray();
    }

    private AIFilterParam enrichWithUserContext(AIFilterParam param) {
        if (param == null) {
            return new AIFilterParam();
        }
        String resumeContent = param.getResumeContent();
        String preference = param.getPreference();
        Long userId = HeaderContext.getHeader().getUserId();
        if (userId != null && userId > 0) {
            // Authenticated requests always use server-owned profile data. The
            // request fields remain useful for isolated tests and old callers
            // that do not carry an authenticated user context.
            resumeContent = "";
            preference = "";
            if (userResumeMapper != null) {
                var resume = userResumeMapper.selectByUserId(userId);
                resumeContent = resume == null ? "" : resume.getResumeContent();
            }
            if (userInfoMapper != null) {
                var preferenceEntity = userInfoMapper.selectPreferenceByUserId(userId);
                preference = preferenceEntity == null ? "" : JSON.toJSONString(preferenceEntity);
            }
        }
        return new AIFilterParam(param.getPrompt(), param.getJobBaseInfo(), param.getJobExtInfo(),
                resumeContent, preference);
    }

    private AiFilterResultVO normalizeStructuredResult(AiFilterResultVO result) {
        if (result == null) {
            throw temporaryFailure();
        }
        String requestedAction = StringUtils.upperCase(StringUtils.trimToEmpty(result.getRecommendedAction()));
        result.setFilter(Boolean.TRUE.equals(result.getFilter()) || "FILTER".equals(requestedAction));
        result.setJobScore(clampScore(result.getJobScore(), result.getFilter() ? 1 : 3));
        result.setCompanyScore(clampScore(result.getCompanyScore(), result.getFilter() ? 2 : 3));
        result.setMatchScore(clampScore(result.getMatchScore(), result.getFilter() ? 1 : 3));
        if (StringUtils.isBlank(result.getReason())) {
            result.setReason(result.getFilter() ? "岗位存在明确风险或不匹配" : "岗位整体可尝试");
        }
        if (result.getGoodSignals() == null) {
            result.setGoodSignals(Collections.emptyList());
        }
        if (result.getRiskSignals() == null) {
            result.setRiskSignals(Collections.emptyList());
        }
        if (result.getPrepSkills() == null) {
            result.setPrepSkills(Collections.emptyList());
        }
        result.setRiskLevel(normalizeRiskLevel(result.getRiskLevel(), result));
        if ("HIGH".equals(result.getRiskLevel())) {
            result.setFilter(true);
        }
        result.setRecommendedAction(normalizeAction(requestedAction, result));
        if (result.getClarificationQuestions() == null) {
            result.setClarificationQuestions(Collections.emptyList());
        }
        if ("CHAT_FIRST".equals(requestedAction)) {
            if (result.getClarificationQuestions().isEmpty()) {
                result.setClarificationQuestions(List.of("岗位信息尚未明确，建议向 HR 确认具体安排。"));
            }
        }
        return result;
    }

    private ApplicationException temporaryFailure() {
        return new ApplicationException(TEMPORARY_FAILURE_MESSAGE);
    }

    private boolean isNonRetryableAiServiceFailure(ApplicationException error) {
        return BizCodeEnum.PRODUCT_NOT_AUTHORIZED.getCode().equals(error.getBizCode())
                || "AI产品能力已过期".equals(error.getMessage())
                || "当前 AI 服务不支持分离系统规则与用户内容".equals(error.getMessage());
    }

    private int clampScore(Integer score, int defaultScore) {
        int value = score == null ? defaultScore : score;
        return Math.max(1, Math.min(5, value));
    }

    private String normalizeRiskLevel(String riskLevel, AiFilterResultVO result) {
        if (Boolean.TRUE.equals(result.getFilter())) {
            return "HIGH";
        }
        String value = StringUtils.trimToEmpty(riskLevel).toUpperCase();
        if ("LOW".equals(value) || "MEDIUM".equals(value) || "HIGH".equals(value)) {
            return value;
        }
        if (result.getRiskSignals() != null && !result.getRiskSignals().isEmpty()) {
            return "MEDIUM";
        }
        if (result.getJobScore() >= 4 && result.getCompanyScore() >= 3 && result.getMatchScore() >= 4) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private String normalizeAction(String recommendedAction, AiFilterResultVO result) {
        if (Boolean.TRUE.equals(result.getFilter())) {
            return "FILTER";
        }
        String value = StringUtils.trimToEmpty(recommendedAction).toUpperCase();
        if ("FILTER".equals(value)) {
            return "FILTER";
        }
        if ("CHAT_FIRST".equals(value)) {
            return "DELIVER";
        }
        if ("DELIVER".equals(value)) {
            return value;
        }
        throw temporaryFailure();
    }

    private void logStructuredResult(AiFilterResultVO result) {
        log.info("AI job screening filter:{} action:{} risk:{} jobScore:{} companyScore:{} matchScore:{} clarificationCount:{} riskSignalCount:{}",
                result.getFilter(), result.getRecommendedAction(), result.getRiskLevel(),
                result.getJobScore(), result.getCompanyScore(), result.getMatchScore(),
                result.getClarificationQuestions().size(), result.getRiskSignals().size());
    }

    public String buildQuestion(AIFilterParam param) {
        if (param == null) {
            param = new AIFilterParam();
        }
        ObjectNode payload = SAFE_JSON.createObjectNode();
        payload.put("screeningRequest", limitPrompt(param.getPrompt()));
        payload.set("jobBaseInfo", parseExternalData(param.getJobBaseInfo(), 12000));
        payload.set("jobExtInfo", parseExternalData(param.getJobExtInfo(), 12000));
        payload.put("resumeContent", limitData(param.getResumeContent(), 12000));
        payload.set("candidatePreferences",
                parseExternalData(buildScreeningPreference(param.getPreference()), 8000));
        try {
            return SAFE_JSON.writeValueAsString(payload);
        } catch (Exception e) {
            throw temporaryFailure();
        }
    }

    private JsonNode parseExternalData(String value, int maxLength) {
        String normalized = limitData(value, maxLength);
        try {
            return SAFE_JSON.readTree(normalized);
        } catch (Exception ignored) {
            return TextNode.valueOf(normalized);
        }
    }

    /**
     * Build a screening-only preference view. Legacy UI fields are retained in
     * the database for compatibility, but disabled values must not become model
     * instructions or silently affect semantic screening.
     */
    private String buildScreeningPreference(String rawPreference) {
        if (StringUtils.isBlank(rawPreference)) {
            return "{}";
        }
        try {
            JsonNode source = SAFE_JSON.readTree(rawPreference);
            if (source == null || !source.isObject()) {
                throw temporaryFailure();
            }
            ObjectNode view = SAFE_JSON.createObjectNode();
            // These four fields are the current semantic preference contract.
            // Keep their names stable, but do not mix chat-only preferences into
            // screening or leak legacy keyword arrays into the AI context.
            copyText(source, view, "jobGoal");
            copyText(source, view, "unacceptableConditions");
            copyText(source, view, "clarifyConditions");
            copyEnabledText(source, view, "srE", "sr");
            copyEnabledText(source, view, "csrE", "csr");
            if (source.path("locationGuardE").asBoolean(false)) {
                view.put("locationGuardE", true);
                copyText(source, view, "resumeCity");
                copyValue(source, view, "targetCities");
                copyValue(source, view, "locationMismatchThreshold");
            }
            return SAFE_JSON.writeValueAsString(view);
        } catch (ApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw temporaryFailure();
        }
    }

    private void copyEnabledList(JsonNode source, ObjectNode target, String enableKey, String valueKey) {
        if (source.path(enableKey).asBoolean(false)) {
            target.put(enableKey, true);
            copyValue(source, target, valueKey);
        }
    }

    private void copyEnabledText(JsonNode source, ObjectNode target, String enableKey, String valueKey) {
        if (source.path(enableKey).asBoolean(false)) {
            target.put(enableKey, true);
            copyText(source, target, valueKey);
        }
    }

    private void copyText(JsonNode source, ObjectNode target, String key) {
        String value = PromptDataSanitizer.removeLegacyReplyLengthDirectives(
                StringUtils.trimToEmpty(source.path(key).asText()));
        if (StringUtils.isNotBlank(value)) {
            target.put(key, value);
        }
    }

    private void copyValue(JsonNode source, ObjectNode target, String key) {
        JsonNode value = source.get(key);
        if (value != null && !value.isNull()) {
            target.set(key, value);
        }
    }

    private String limitPrompt(String prompt) {
        if (StringUtils.isBlank(prompt)) {
            return "未提供额外筛选条件；按系统宽放行规则结合完整职位信息判断。";
        }
        String normalized = prompt.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]+", " ").trim();
        return keepHeadAndTail(normalized, 5000);
    }

    private String limitData(String value, int maxLength) {
        if (StringUtils.isBlank(value)) {
            return "未提供";
        }
        String normalized = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]+", " ").trim();
        return keepHeadAndTail(normalized, maxLength);
    }

    /**
     * 超长时保留开头和结尾、省略中间，而不是报错：否则长简历/长岗位描述的用户每次筛选都失败。
     * 保留结尾是因为岗位风险条款（如收费入职）常写在描述末尾。
     */
    static String keepHeadAndTail(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        String marker = "\n……（中间内容过长已省略）……\n";
        int head = (maxLength - marker.length()) * 2 / 3;
        int tail = maxLength - marker.length() - head;
        return value.substring(0, head) + marker + value.substring(value.length() - tail);
    }

    /**
     * Models often wrap an otherwise valid JSON answer in a single ```json fence. Unwrap only that
     * exact shape; any other prose or extra objects still fail the strict parse and get retried.
     */
    static String stripWholeCodeFence(String answer) {
        java.util.regex.Matcher matcher = WHOLE_CODE_FENCE.matcher(answer);
        return matcher.matches() ? matcher.group(1) : answer;
    }
}
