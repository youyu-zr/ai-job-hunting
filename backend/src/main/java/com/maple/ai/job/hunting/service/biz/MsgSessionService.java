package com.maple.ai.job.hunting.service.biz;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.emums.AiSeatStatusEnum;
import com.maple.ai.job.hunting.emums.MsgSessionStatusEnum;
import com.maple.ai.job.hunting.entity.MsgSessionDO;
import com.maple.ai.job.hunting.frame.cache.AiJobGateStopCache;
import com.maple.ai.job.hunting.frame.cache.MsgSessionStopCache;
import com.maple.ai.job.hunting.frame.cache.SessionKeyIdCache;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.MsgSessionMapper;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.vo.AiSeatRuntimeStatusVO;
import com.maple.ai.job.hunting.service.ai.impl.OpenAIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import jakarta.annotation.Resource;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author maple
 * Created Date: 2024/5/14 14:43
 * Description:
 */

@Service
@Slf4j
@RequiredArgsConstructor
public class MsgSessionService {

    private final @Nonnull MsgSessionStopCache msgSessionStopCache;

    private final @Nonnull AiJobGateStopCache aiJobGateStopCache;

    private final @Nonnull MsgSessionMapper msgSessionMapper;

    private final @Nonnull UserInfoMapper userInfoMapper;

    private final @Nonnull SessionKeyIdCache sessionKeyIdCache;

    @Resource(name = "openai")
    private OpenAIService openAIService;


    /** Global AI-seat stop state is isolated per authenticated user. */
    private final Map<Long, Boolean> globalStopByUser = new ConcurrentHashMap<>();


    public boolean msgSessionStop(String jobKey) {
        Long userId = HeaderContext.getHeader().getUserId();
        if (isGlobalStop(userId)) {
            return true;
        }

        // 兼容调试
        if (jobKey != null && jobKey.contains("ask-debug-")) {
            return false;
        }

        UserInfoDO userInfoDO = userInfoMapper.selectById(userId);
        // 用户全局停止时，停止会话
        if (!AiSeatStatusEnum.OPEN.getCode().equals(userInfoDO.getAiSeatStatus())) {
            return true;
        }

        Boolean manualStop = msgSessionStopCache.get(scopedStopCacheKey(userId, jobKey));
        Boolean aiGateStop = aiJobGateStopCache.get(aiGateCacheKey(jobKey));
        return Boolean.TRUE.equals(manualStop) || Boolean.TRUE.equals(aiGateStop);
    }

    public boolean stopUserAllSession(Long userId) {
        // 查询出当前用户的所有会话（最近使用，活跃）
        LambdaQueryWrapper<MsgSessionDO> condition = new LambdaQueryWrapper<>();
        condition.eq(MsgSessionDO::getUserId, userId);
        condition.eq(MsgSessionDO::getStatus, MsgSessionStatusEnum.IN_USE.getCode());
        condition.select(MsgSessionDO::getSessionKey);
        List<MsgSessionDO> sessionDOList = msgSessionMapper.selectList(condition);
        sessionDOList.stream()
                .map(MsgSessionDO::getSessionKey)
                .map(jobKey -> scopedStopCacheKey(userId, jobKey))
                .forEach(jobKey -> msgSessionStopCache.set(jobKey, true));
        return true;
    }

    /** Resume only automatic AI gate stops after the global seat is enabled.
     * Manual pauses are intentionally preserved for the user's current
     * conversation control.
     */
    public boolean resumeUserAiJobGates(Long userId) {
        String prefix = String.valueOf(userId) + ":";
        aiJobGateStopCache.toMap().keySet().stream()
                .filter(key -> String.valueOf(key).startsWith(prefix))
                .forEach(aiJobGateStopCache::delete);
        globalStopByUser.remove(userId);
        return true;
    }

    public boolean setMsgSessionStop(String jobKey, Boolean isStop) {
        Long userId = HeaderContext.getHeader().getUserId();
        if ("globalJobKey".equals(jobKey)) {
            if (Boolean.TRUE.equals(isStop)) {
                globalStopByUser.put(userId, true);
            } else {
                globalStopByUser.remove(userId);
            }
            return true;
        }


        // 还没有会话记录时，如果是停止请求，也先写入停止缓存，避免垃圾岗位后续消息继续唤起AI坐席。
        if (!existMsgSession(jobKey)) {
            if (Boolean.TRUE.equals(isStop)) {
                return msgSessionStopCache.set(scopedStopCacheKey(userId, jobKey), true);
            }
            throw new ApplicationException("会话不存在;无需修改状态", 200);
        }

        if (Boolean.TRUE.equals(isStop)) {
            Optional.ofNullable(userInfoMapper.selectById(HeaderContext.getHeader().getUserId()))
                    .map(UserInfoDO::getAiSeatStatus)
                    .filter(status -> !AiSeatStatusEnum.OPEN.getCode().equals(status)).ifPresent(status -> {
                        throw new ApplicationException("全局AI坐席开关已关闭");
                    });
        }

        boolean result = msgSessionStopCache.set(scopedStopCacheKey(userId, jobKey), isStop);
        if (Boolean.FALSE.equals(isStop)) {
            aiJobGateStopCache.delete(aiGateCacheKey(jobKey));
        }
        return result;
    }

    @SuppressWarnings("all")
    public boolean trySetMsgSessionStop(String jobKey, Boolean isStop) {
        boolean result = false;
        try {
            result = setMsgSessionStop(jobKey, isStop);
        } catch (ApplicationException e) {
            if ("会话不存在;无需修改状态".equals(e.getMessage())) {
                log.info("会话不存在，跳过会话停止修改 jobKey:{} isStop:{}", jobKey, isStop);
            } else {
                log.error("设置会话停止失败", e);
            }
        } catch (Exception e) {
            log.error("设置会话停止失败", e);
        }
        return result;
    }

    public boolean isAiJobGateStopOnly(String jobKey) {
        Long userId = HeaderContext.getHeader().getUserId();
        if (isGlobalStop(userId) || (jobKey != null && jobKey.contains("ask-debug-"))) {
            return false;
        }
        UserInfoDO userInfoDO = userInfoMapper.selectById(userId);
        if (!AiSeatStatusEnum.OPEN.getCode().equals(userInfoDO.getAiSeatStatus())) {
            return false;
        }
        return !Boolean.TRUE.equals(msgSessionStopCache.get(scopedStopCacheKey(userId, jobKey)))
                && Boolean.TRUE.equals(aiJobGateStopCache.get(aiGateCacheKey(jobKey)));
    }

    public AiSeatRuntimeStatusVO getRuntimeStatus(String jobKey) {
        Long userId = HeaderContext.getHeader().getUserId();
        UserInfoDO userInfoDO = userInfoMapper.selectById(userId);
        boolean globalEnabled = userInfoDO != null
                && AiSeatStatusEnum.OPEN.getCode().equals(userInfoDO.getAiSeatStatus());
        boolean manuallyPaused = Boolean.TRUE.equals(msgSessionStopCache.get(scopedStopCacheKey(userId, jobKey)));
        boolean gatePaused = Boolean.TRUE.equals(aiJobGateStopCache.get(aiGateCacheKey(jobKey)));
        return AiSeatRuntimeStatusVO.resolve(globalEnabled, existMsgSession(jobKey), manuallyPaused, gatePaused);
    }

    public boolean trySetAiJobGateStop(String jobKey, Boolean isStop) {
        if (Boolean.TRUE.equals(isStop)) {
            return aiJobGateStopCache.set(aiGateCacheKey(jobKey), true);
        }
        return aiJobGateStopCache.delete(aiGateCacheKey(jobKey));
    }

    private String aiGateCacheKey(String jobKey) {
        Long userId = HeaderContext.getHeader().getUserId();
        return String.valueOf(userId) + ":" + StringUtils.defaultString(jobKey);
    }

    static String scopedStopCacheKey(Long userId, String jobKey) {
        return String.valueOf(userId) + ":" + StringUtils.defaultString(jobKey);
    }

    private boolean isGlobalStop(Long userId) {
        return Boolean.TRUE.equals(globalStopByUser.get(userId));
    }


    private boolean existMsgSession(String jobKey) {
        Long userId = HeaderContext.getHeader().getUserId();
        MsgSessionDO sessionDO = msgSessionMapper.selectInUseByUserAndSessionKey(userId, jobKey);
        if (sessionDO == null) {
            return false;
        }

        String sessionId = sessionDO.getId() == null
                ? sessionKeyIdCache.get(jobKey)
                : String.valueOf(sessionDO.getId());
        return StringUtils.isNotBlank(sessionId)
                && openAIService.getMsgSessionMap().containsKey(sessionId);
    }
}
