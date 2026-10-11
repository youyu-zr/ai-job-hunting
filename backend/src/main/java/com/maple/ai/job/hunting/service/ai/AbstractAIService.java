package com.maple.ai.job.hunting.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.consts.AIPromptStrConstant;
import com.maple.ai.job.hunting.consts.CommunicationStylePrompt;
import com.maple.ai.job.hunting.mapper.MsgSessionMapper;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.model.bo.UserResumeDO;
import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.utils.PromptDataSanitizer;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author maple
 * Created Date: 2024/5/15 11:14
 * Description:
 */

public abstract class AbstractAIService implements AIService {

    @Resource
    protected MsgSessionMapper msgSessionMapper;
    @Resource
    protected UserResumeMapper userResumeMapper;
    @Resource
    private UserInfoMapper userInfoMapper;


    public String buildSystemFirstPrompt() {
        // 载入简历，作为系统提示上下文
        LambdaQueryWrapper<UserResumeDO> condition = new LambdaQueryWrapper<>();
        condition.eq(UserResumeDO::getUserId, HeaderContext.getHeader().getUserId());
        UserResumeDO userResumeDO = userResumeMapper.selectOne(condition);
        Map<String, String> promptMap = Arrays.stream(AIPromptStrConstant.AI_SEAT_SYSTEM_PROMPT.split(AIPromptStrConstant.SPLIT_SYMBOL))
                .map(s -> AIPromptStrConstant.SPLIT_SYMBOL + s).collect(Collectors.toMap(s -> s.split("\n")[0], s -> s));

        String systemPrompt = promptMap.get(AIPromptStrConstant.ROLE_SETTING_TITLE);

        // 载入偏好设置，作为系统提示上下文
        PreferenceEntity preferenceEntity = Optional.ofNullable(userInfoMapper.selectPreferenceByUserId(HeaderContext.getHeader().getUserId()))
                .orElse(new PreferenceEntity());

        String communicationStyle = CommunicationStylePrompt.resolve(preferenceEntity.getCommunicationStyle());
        String seekingRequirements = buildSeekingRequirements(preferenceEntity);

        return joinAndWrap(systemPrompt, communicationStyle, seekingRequirements,
                AIPromptStrConstant.RESUME_TITLE, userResumeDO.getResumeContent());
    }

    private String buildSeekingRequirements(PreferenceEntity preference) {
        StringBuilder result = new StringBuilder("【求职要求】\n");
        appendRequirement(result, "求职目标", preference.getJobGoal());
        appendRequirement(result, "明确不能接受", preference.getUnacceptableConditions());
        appendRequirement(result, "需要先了解", preference.getClarifyConditions());
        appendRequirement(result, "沟通与面试偏好", preference.getCommunicationPreferences());
        if (result.length() == "【求职要求】\n".length()) {
            return "";
        }
        result.append("以上内容用于岗位筛选和相关沟通，不是简历事实；只有话题相关时才使用，不要一次性全部复述给HR。\n");
        return result.toString();
    }

    private void appendRequirement(StringBuilder result, String label, String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        result.append("- ").append(label).append("：").append(limitUserPrompt(value)).append('\n');
    }

    private String limitUserPrompt(String rawPrompt) {
        String normalized = PromptDataSanitizer.removeLegacyReplyLengthDirectives(rawPrompt);
        return normalized.length() > 1000 ? normalized.substring(0, 1000) : normalized;
    }

    private String joinAndWrap(String... strArr) {
        List<String> filtered = Arrays.stream(strArr)
                .filter(StringUtils::isNotBlank)
                .toList();

        if (filtered.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        result.append(filtered.get(0));
        boolean prevEndsWithNewline = filtered.get(0).endsWith("\n");

        for (int i = 1; i < filtered.size(); i++) {
            String current = filtered.get(i);
            boolean currentStartsWithNewline = current.startsWith("\n");
            boolean currentEndsWithNewline = current.endsWith("\n");

            // 如果前一个字符串结尾或当前字符串开头有换行，则不添加额外换行符
            if (!prevEndsWithNewline && !currentStartsWithNewline) {
                result.append("\n");
            }
            result.append(current);
            prevEndsWithNewline = currentEndsWithNewline;
        }

        return result.toString();
    }

}
