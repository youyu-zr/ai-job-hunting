package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.frame.cache.AiJobGateStopCache;
import com.maple.ai.job.hunting.frame.cache.MsgSessionStopCache;
import com.maple.ai.job.hunting.frame.cache.QuestionCache;
import com.maple.ai.job.hunting.frame.cache.SessionKeyIdCache;
import com.maple.ai.job.hunting.mapper.MsgSessionMapper;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.model.ChatSessionResult;
import com.maple.ai.job.hunting.model.param.JobSeekerClonedParam;
import com.maple.ai.job.hunting.model.vo.JobSeekerClonedResultVO;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.ai.AIServiceFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobSeekerClonedServiceHumanHandoffTest {

    @AfterEach
    void clearHeader() {
        HeaderContext.clear();
    }

    @Test
    void explicitContactExchangePausesTheJobBeforeCallingAi() {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.setHeader(new Header(user, "request", "/api/job/seeker/cloned/ask", null, false, null, null));

        UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
        when(userInfoMapper.selectPreferenceOptByUserId(42L)).thenReturn(Optional.empty());

        RecordingMsgSessionService sessions = new RecordingMsgSessionService(
                mock(MsgSessionMapper.class), userInfoMapper);
        RecordingAIServiceFacade ai = new RecordingAIServiceFacade();
        JobSeekerClonedService service = new JobSeekerClonedService();
        ReflectionTestUtils.setField(service, "msgSessionService", sessions);
        ReflectionTestUtils.setField(service, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(service, "questionCache", new QuestionCache());
        ReflectionTestUtils.setField(service, "aiServiceFacade", ai);

        JobSeekerClonedResultVO result = service.ask(new JobSeekerClonedParam(
                "方便交换微信吗？", "job-42", null));

        assertEquals("HUMAN_HANDOFF", result.getStopReason());
        assertEquals("job-42", sessions.pausedJobKey);
        assertTrue(sessions.paused);
        assertEquals(0, ai.sessionChatCalls.get());
    }

    private static final class RecordingMsgSessionService extends MsgSessionService {
        private String pausedJobKey;
        private boolean paused;

        private RecordingMsgSessionService(MsgSessionMapper mapper, UserInfoMapper userInfoMapper) {
            super(new MsgSessionStopCache(), new AiJobGateStopCache(), mapper, userInfoMapper, new SessionKeyIdCache());
        }

        @Override
        public boolean msgSessionStop(String jobKey) {
            return false;
        }

        @Override
        public boolean isAiJobGateStopOnly(String jobKey) {
            return false;
        }

        @Override
        public boolean trySetMsgSessionStop(String jobKey, Boolean isStop) {
            pausedJobKey = jobKey;
            paused = Boolean.TRUE.equals(isStop);
            return true;
        }
    }

    private static final class RecordingAIServiceFacade extends AIServiceFacade {
        private final AtomicInteger sessionChatCalls = new AtomicInteger();

        @Override
        public ChatSessionResult sessionChat(String sessionId, String ask) {
            sessionChatCalls.incrementAndGet();
            return new ChatSessionResult();
        }
    }
}
