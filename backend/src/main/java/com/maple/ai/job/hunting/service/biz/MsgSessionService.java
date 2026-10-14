package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.emums.AiSeatStatusEnum;
import com.maple.ai.job.hunting.emums.MsgSessionStatusEnum;
import com.maple.ai.job.hunting.entity.MsgSessionDO;
import com.maple.ai.job.hunting.frame.cache.AiJobGateStopCache;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.MsgSessionMapper;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.model.vo.AiSeatRuntimeStatusVO;
import com.maple.ai.job.hunting.service.ai.impl.OpenAIService;
import com.maple.ai.job.hunting.utils.LogValueSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class MsgSessionService {

    private final @Nonnull AiJobGateStopCache aiJobGateStopCache;
    private final @Nonnull MsgSessionMapper msgSessionMapper;
    private final @Nonnull UserInfoMapper userInfoMapper;

    @Resource(name = "openai")
    private OpenAIService openAIService;

    /** Legacy endpoint state only; regular account state is persisted in user_info. */
    private final Map<Long, Boolean> globalStopByUser = new ConcurrentHashMap<>();

    public boolean msgSessionStop(String jobKey) {
        Long userId = HeaderContext.getHeader().getUserId();
        if (isGlobalStop(userId)) {
            return true;
        }
        if (jobKey != null && jobKey.contains("ask-debug-")) {
            return false;
        }
        UserInfoDO userInfo = userInfoMapper.selectById(userId);
        if (userInfo == null || !AiSeatStatusEnum.OPEN.getCode().equals(userInfo.getAiSeatStatus())) {
            return true;
        }
        MsgSessionDO session = selectSession(userId, jobKey);
        boolean manuallyPaused = session != null
                && MsgSessionStatusEnum.is(session.getStatus(), MsgSessionStatusEnum.MANUALLY_PAUSED);
        return manuallyPaused || Boolean.TRUE.equals(aiJobGateStopCache.get(aiGateCacheKey(userId, jobKey)));
    }

    /** Persist the global stop for every currently active conversation. */
    public boolean stopUserAllSession(Long userId) {
        msgSessionMapper.updateUserSessionStatuses(userId,
                MsgSessionStatusEnum.IN_USE, MsgSessionStatusEnum.MANUALLY_PAUSED);
        return true;
    }

    /** Re-enable the account gate and clear only automatic gate stops. */
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

        MsgSessionDO session = selectSession(userId, jobKey);
        if (Boolean.TRUE.equals(isStop)) {
            UserInfoDO userInfo = userInfoMapper.selectById(userId);
            if (userInfo == null || !AiSeatStatusEnum.OPEN.getCode().equals(userInfo.getAiSeatStatus())) {
                throw new ApplicationException("全局AI坐席开关已关闭");
            }
            if (session == null) {
                MsgSessionDO placeholder = new MsgSessionDO();
                placeholder.setUserId(userId);
                placeholder.setSessionKey(jobKey);
                placeholder.setMsgContext("");
                placeholder.setStatus(MsgSessionStatusEnum.MANUALLY_PAUSED.getCode());
                return msgSessionMapper.insert(placeholder) > 0;
            }
            if (MsgSessionStatusEnum.is(session.getStatus(), MsgSessionStatusEnum.MANUALLY_PAUSED)) {
                return true;
            }
            return msgSessionMapper.updateSessionStatus(session.getId(), userId,
                    MsgSessionStatusEnum.IN_USE, MsgSessionStatusEnum.MANUALLY_PAUSED) > 0;
        }

        if (session == null) {
            throw new ApplicationException("会话不存在;无需修改状态", 200);
        }
        if (MsgSessionStatusEnum.is(session.getStatus(), MsgSessionStatusEnum.MANUALLY_PAUSED)) {
            int updated = msgSessionMapper.updateSessionStatus(session.getId(), userId,
                    MsgSessionStatusEnum.MANUALLY_PAUSED, MsgSessionStatusEnum.IN_USE);
            if (updated == 0) {
                MsgSessionDO current = selectSession(userId, jobKey);
                if (current == null
                        || MsgSessionStatusEnum.is(current.getStatus(), MsgSessionStatusEnum.MANUALLY_PAUSED)) {
                    return false;
                }
                session = current;
            } else {
                session.setStatus(MsgSessionStatusEnum.encode(
                        MsgSessionStatusEnum.revision(session.getStatus()) + 1L,
                        MsgSessionStatusEnum.IN_USE));
            }
        }
        if (openAIService != null) {
            openAIService.restoreSession(session);
        }
        aiJobGateStopCache.delete(aiGateCacheKey(userId, jobKey));
        return true;
    }

    public boolean trySetMsgSessionStop(String jobKey, Boolean isStop) {
        try {
            return setMsgSessionStop(jobKey, isStop);
        } catch (ApplicationException e) {
            if ("会话不存在;无需修改状态".equals(e.getMessage())) {
                log.info("会话不存在，跳过会话停止修改 jobKey:{} isStop:{}",
                        LogValueSanitizer.sanitize(jobKey, 160), isStop);
            } else {
                log.error("设置会话停止失败", e);
            }
        } catch (Exception e) {
            log.error("设置会话停止失败 errorType:{}", e.getClass().getSimpleName());
        }
        return false;
    }

    public boolean isAiJobGateStopOnly(String jobKey) {
        Long userId = HeaderContext.getHeader().getUserId();
        if (isGlobalStop(userId) || (jobKey != null && jobKey.contains("ask-debug-"))) {
            return false;
        }
        UserInfoDO userInfo = userInfoMapper.selectById(userId);
        if (userInfo == null || !AiSeatStatusEnum.OPEN.getCode().equals(userInfo.getAiSeatStatus())) {
            return false;
        }
        MsgSessionDO session = selectSession(userId, jobKey);
        boolean manuallyPaused = session != null
                && MsgSessionStatusEnum.is(session.getStatus(), MsgSessionStatusEnum.MANUALLY_PAUSED);
        return !manuallyPaused
                && Boolean.TRUE.equals(aiJobGateStopCache.get(aiGateCacheKey(userId, jobKey)));
    }

    public AiSeatRuntimeStatusVO getRuntimeStatus(String jobKey) {
        Long userId = HeaderContext.getHeader().getUserId();
        UserInfoDO userInfo = userInfoMapper.selectById(userId);
        boolean globalEnabled = userInfo != null
                && AiSeatStatusEnum.OPEN.getCode().equals(userInfo.getAiSeatStatus());
        MsgSessionDO session = selectSession(userId, jobKey);
        boolean manuallyPaused = session != null
                && MsgSessionStatusEnum.is(session.getStatus(), MsgSessionStatusEnum.MANUALLY_PAUSED);
        boolean gatePaused = Boolean.TRUE.equals(aiJobGateStopCache.get(aiGateCacheKey(userId, jobKey)));
        boolean sessionExists = session != null && session.getId() != null
                && openAIService != null
                && openAIService.getMsgSessionMap().containsKey(String.valueOf(session.getId()));
        long globalRevision = Optional.ofNullable(userInfoMapper.selectPreferenceOptByUserId(userId))
                .orElseGet(java.util.Optional::empty)
                .map(PreferenceEntity::getAiSeatRevision)
                .filter(revision -> revision != null && revision >= 0)
                .orElse(0L);
        return AiSeatRuntimeStatusVO.resolve(globalEnabled, sessionExists, manuallyPaused, gatePaused,
                globalRevision, sessionRevision(session));
    }

    public boolean trySetAiJobGateStop(String jobKey, Boolean isStop) {
        if (Boolean.TRUE.equals(isStop)) {
            return aiJobGateStopCache.set(aiGateCacheKey(
                    HeaderContext.getHeader().getUserId(), jobKey), true);
        }
        return aiJobGateStopCache.delete(aiGateCacheKey(
                HeaderContext.getHeader().getUserId(), jobKey));
    }

    private String aiGateCacheKey(Long userId, String jobKey) {
        return String.valueOf(userId) + ":" + StringUtils.defaultString(jobKey);
    }

    static String scopedStopCacheKey(Long userId, String jobKey) {
        return String.valueOf(userId) + ":" + StringUtils.defaultString(jobKey);
    }

    private MsgSessionDO selectSession(Long userId, String jobKey) {
        return msgSessionMapper.selectLatestByUserAndSessionKey(userId, jobKey);
    }

    private long sessionRevision(MsgSessionDO session) {
        return session == null ? 0L : MsgSessionStatusEnum.revision(session.getStatus());
    }

    private boolean isGlobalStop(Long userId) {
        return Boolean.TRUE.equals(globalStopByUser.get(userId));
    }
}
