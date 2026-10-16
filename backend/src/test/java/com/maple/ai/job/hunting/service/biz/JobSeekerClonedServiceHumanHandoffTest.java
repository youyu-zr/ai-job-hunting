package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.frame.cache.AiJobGateStopCache;
import com.maple.ai.job.hunting.frame.cache.QuestionCache;
import com.maple.ai.job.hunting.frame.cache.SessionKeyIdCache;
import com.maple.ai.job.hunting.mapper.MsgSessionMapper;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.model.ChatSessionResult;
import com.maple.ai.job.hunting.model.param.JobSeekerClonedParam;
import com.maple.ai.job.hunting.model.vo.JobSeekerClonedResultVO;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Action;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Category;
import com.maple.ai.job.hunting.service.ai.AIServiceFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobSeekerClonedServiceHumanHandoffTest {

    @AfterEach
    void clearHeader() {
        HeaderContext.clear();
    }

    @Test
    void ordinaryContactExchangeContinuesIntoAiWithoutPausingTheJob() {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.setHeader(new Header(user, "request", "/api/job/seeker/cloned/ask", null, false, null, null));

        UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
        UserResumeMapper userResumeMapper = mock(UserResumeMapper.class);
        when(userInfoMapper.selectPreferenceOptByUserId(42L)).thenReturn(Optional.empty());

        RecordingMsgSessionService sessions = new RecordingMsgSessionService(
                mock(MsgSessionMapper.class), userInfoMapper);
        RecordingAIServiceFacade ai = new RecordingAIServiceFacade();
        JobSeekerClonedService service = new JobSeekerClonedService() {
            @Override
            protected String getOrInitSession(String jobKey, Long userId) {
                return "session-" + jobKey;
            }
        };
        MessageTriageService triage = new MessageTriageService() {
            @Override
            public MessageTriageResultVO classify(String question, String jobContext, String resumeContent,
                                                  com.maple.ai.job.hunting.model.entity.PreferenceEntity preference,
                                                  Integer platformBodyType) {
                return MessageTriageResultVO.builder()
                        .category(Category.NORMAL_JOB)
                        .action(Action.REPLY)
                        .reasonCode("TEST")
                        .plainLanguageReason("test")
                        .clarificationQuestions(java.util.List.of())
                        .evidence(java.util.List.of())
                        .build();
            }
        };
        ReflectionTestUtils.setField(service, "messageTriageService", triage);
        ReflectionTestUtils.setField(service, "msgSessionService", sessions);
        ReflectionTestUtils.setField(service, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(service, "userResumeMapper", userResumeMapper);
        ReflectionTestUtils.setField(service, "questionCache", new QuestionCache());
        ReflectionTestUtils.setField(service, "highInterestInformCache", new com.maple.ai.job.hunting.frame.cache.HighInterestInformCache());
        ReflectionTestUtils.setField(service, "aiServiceFacade", ai);

        JobSeekerClonedResultVO result = service.ask(new JobSeekerClonedParam(
                "方便交换微信吗？", "job-42", null));

        assertNull(result.getStopReason());
        assertNull(sessions.pausedJobKey);
        assertFalse(sessions.paused);
        assertEquals(1, ai.sessionChatCalls.get());
    }

    private static final class RecordingMsgSessionService extends MsgSessionService {
        private String pausedJobKey;
        private boolean paused;

        private RecordingMsgSessionService(MsgSessionMapper mapper, UserInfoMapper userInfoMapper) {
            super(new AiJobGateStopCache(), mapper, userInfoMapper);
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
            ChatSessionResult result = new ChatSessionResult();
            result.setAnswer("好的，我们先了解一下岗位安排。");
            return result;
        }
    }
}
