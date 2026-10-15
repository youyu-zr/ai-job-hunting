package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.frame.cache.AiJobGateStopCache;
import com.maple.ai.job.hunting.frame.cache.HighInterestInformCache;
import com.maple.ai.job.hunting.frame.cache.QuestionCache;
import com.maple.ai.job.hunting.frame.cache.SessionKeyIdCache;
import com.maple.ai.job.hunting.mapper.MsgSessionMapper;
import com.maple.ai.job.hunting.mapper.UserAIConfigMapper;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.model.ChatSessionResult;
import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.model.param.JobSeekerClonedParam;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.ai.AIServiceFacade;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Action;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Category;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobSeekerClonedServiceMarketingTest {

    @AfterEach
    void clearHeader() {
        HeaderContext.clear();
    }

    @Test
    void defaultMarketingReplySettingSilencesPromotionalCtaBeforeCallingAi() {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.setHeader(new Header(user, "request", "/api/job/seeker/cloned/ask", null, false, null, null));

        UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
        UserResumeMapper userResumeMapper = mock(UserResumeMapper.class);
        MsgSessionMapper msgSessionMapper = mock(MsgSessionMapper.class);
        PreferenceEntity preference = new PreferenceEntity();
        preference.setMarketingReplyE(false);
        when(userInfoMapper.selectPreferenceOptByUserId(42L)).thenReturn(Optional.of(preference));

        RecordingAIServiceFacade ai = new RecordingAIServiceFacade();
        JobSeekerClonedService service = new JobSeekerClonedService();
        MsgSessionService sessions = new RecordingMsgSessionService(
                msgSessionMapper, userInfoMapper);
        ReflectionTestUtils.setField(service, "messageTriageService", triage(Category.PROMOTION, Action.SILENT_CURRENT_MESSAGE));
        ReflectionTestUtils.setField(service, "msgSessionService", sessions);
        ReflectionTestUtils.setField(service, "msgSessionMapper", msgSessionMapper);
        ReflectionTestUtils.setField(service, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(service, "userResumeMapper", userResumeMapper);
        ReflectionTestUtils.setField(service, "questionCache", new QuestionCache());
        ReflectionTestUtils.setField(service, "aiServiceFacade", ai);

        var result = service.ask(new JobSeekerClonedParam(
                "扫码进群领取资料，注册后安排推广任务", "job-42", null));

        assertEquals("PROMOTION_SILENT", result.getStopReason());
        assertEquals(0, ai.sessionChatCalls.get());
    }

    @Test
    void defaultMarketingReplySettingSilencesAddWechatCourseMaterialCtaBeforeCallingAi() {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.setHeader(new Header(user, "request", "/api/job/seeker/cloned/ask", null, false, null, null));

        UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
        UserResumeMapper userResumeMapper = mock(UserResumeMapper.class);
        MsgSessionMapper msgSessionMapper = mock(MsgSessionMapper.class);
        PreferenceEntity preference = new PreferenceEntity();
        preference.setMarketingReplyE(false);
        when(userInfoMapper.selectPreferenceOptByUserId(42L)).thenReturn(Optional.of(preference));

        RecordingAIServiceFacade ai = new RecordingAIServiceFacade();
        JobSeekerClonedService service = new JobSeekerClonedService();
        MsgSessionService sessions = new RecordingMsgSessionService(
                msgSessionMapper, userInfoMapper);
        ReflectionTestUtils.setField(service, "messageTriageService", triage(Category.PROMOTION, Action.SILENT_CURRENT_MESSAGE));
        ReflectionTestUtils.setField(service, "msgSessionService", sessions);
        ReflectionTestUtils.setField(service, "msgSessionMapper", msgSessionMapper);
        ReflectionTestUtils.setField(service, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(service, "userResumeMapper", userResumeMapper);
        ReflectionTestUtils.setField(service, "questionCache", new QuestionCache());
        ReflectionTestUtils.setField(service, "aiServiceFacade", ai);

        var result = service.ask(new JobSeekerClonedParam(
                "课程顾问岗位，添加微信领取课程资料", "job-42", null));

        assertEquals("PROMOTION_SILENT", result.getStopReason());
        assertEquals(0, ai.sessionChatCalls.get());
    }

    @Test
    void enablingOptionalMarketingRepliesDoesNotBypassHardPromotionRisk() {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.setHeader(new Header(user, "request", "/api/job/seeker/cloned/ask", null, false, null, null));

        UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
        UserResumeMapper userResumeMapper = mock(UserResumeMapper.class);
        PreferenceEntity preference = new PreferenceEntity();
        preference.setMarketingReplyE(true);
        when(userInfoMapper.selectPreferenceOptByUserId(42L)).thenReturn(Optional.of(preference));

        RecordingAIServiceFacade ai = new RecordingAIServiceFacade();
        JobSeekerClonedService service = new JobSeekerClonedService();
        MsgSessionService sessions = new RecordingMsgSessionService(
                mock(MsgSessionMapper.class), userInfoMapper);
        ReflectionTestUtils.setField(service, "messageTriageService", triage(Category.HIGH_RISK, Action.BLOCK_CURRENT_JOB));
        ReflectionTestUtils.setField(service, "msgSessionService", sessions);
        ReflectionTestUtils.setField(service, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(service, "userResumeMapper", userResumeMapper);
        ReflectionTestUtils.setField(service, "questionCache", new QuestionCache());
        ReflectionTestUtils.setField(service, "aiServiceFacade", ai);

        var result = service.ask(new JobSeekerClonedParam(
                "先充值成为代理，邀请好友返佣", "job-42", null));

        assertEquals("HIGH_RISK_JOB", result.getStopReason());
        assertEquals(0, ai.sessionChatCalls.get());
    }

    @Test
    void enablingOptionalMarketingRepliesAllowsOrdinaryPromotionConversationToReachAi() {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.setHeader(new Header(user, "request", "/api/job/seeker/cloned/ask", null, false, null, null));

        UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
        UserResumeMapper userResumeMapper = mock(UserResumeMapper.class);
        PreferenceEntity preference = new PreferenceEntity();
        preference.setMarketingReplyE(true);
        when(userInfoMapper.selectPreferenceOptByUserId(42L)).thenReturn(Optional.of(preference));

        RecordingAIServiceFacade ai = new RecordingAIServiceFacade();
        JobSeekerClonedService service = new JobSeekerClonedService();
        MsgSessionMapper msgSessionMapper = mock(MsgSessionMapper.class);
        com.maple.ai.job.hunting.entity.MsgSessionDO existingSession =
                new com.maple.ai.job.hunting.entity.MsgSessionDO();
        existingSession.setId(1L);
        when(msgSessionMapper.selectOne(any())).thenReturn(existingSession);
        when(msgSessionMapper.selectInUseByUserAndSessionKey(42L, "job-42")).thenReturn(existingSession);
        ReflectionTestUtils.setField(service, "messageTriageService", new RecordingTriageService());
        ReflectionTestUtils.setField(service, "msgSessionService",
                new RecordingMsgSessionService(msgSessionMapper, userInfoMapper));
        ReflectionTestUtils.setField(service, "msgSessionMapper", msgSessionMapper);
        ReflectionTestUtils.setField(service, "sessionKeyIdCache", new SessionKeyIdCache());
        ReflectionTestUtils.setField(service, "highInterestInformCache", new HighInterestInformCache());
        ReflectionTestUtils.setField(service, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(service, "userResumeMapper", userResumeMapper);
        ReflectionTestUtils.setField(service, "questionCache", new QuestionCache());
        ReflectionTestUtils.setField(service, "aiServiceFacade", ai);

        var feeResult = service.ask(new JobSeekerClonedParam(
                "缴纳入会费后开始接单", "job-42", null));

        assertEquals("PROMOTION_SILENT", feeResult.getStopReason());
        assertEquals(0, ai.sessionChatCalls.get());

        service.ask(new JobSeekerClonedParam(
                "这是岗位推广活动，扫码了解品牌活动安排", "job-42", null));

        assertEquals(0, ai.sessionChatCalls.get());
    }

    private static MessageTriageService triage(Category category, Action action) {
        MessageTriageResultVO result = MessageTriageResultVO.builder()
                .category(category)
                .action(action)
                .reasonCode("TEST")
                .plainLanguageReason("test")
                .clarificationQuestions(java.util.List.of())
                .evidence(java.util.List.of())
                .build();
        return new RecordingTriageService(result);
    }

    private static final class RecordingMsgSessionService extends MsgSessionService {
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
    }

    private static final class RecordingAIServiceFacade extends AIServiceFacade {
        private final AtomicInteger sessionChatCalls = new AtomicInteger();

        @Override
        public ChatSessionResult sessionChat(String sessionId, String ask) {
            sessionChatCalls.incrementAndGet();
            return new ChatSessionResult();
        }
    }

    private static final class RecordingTriageService extends MessageTriageService {
        private final MessageTriageResultVO result;

        private RecordingTriageService() {
            this(MessageTriageResultVO.builder()
                    .category(Category.PROMOTION)
                    .action(Action.SILENT_CURRENT_MESSAGE)
                    .reasonCode("TEST")
                    .plainLanguageReason("test")
                    .clarificationQuestions(java.util.List.of())
                    .evidence(java.util.List.of())
                    .build());
        }

        private RecordingTriageService(MessageTriageResultVO result) {
            this.result = result;
        }

        @Override
        public MessageTriageResultVO classify(String question, String jobContext, String resumeContent,
                                              PreferenceEntity preference, Integer platformBodyType) {
            if (question != null && question.contains("先交")) {
                return MessageTriageResultVO.builder()
                        .category(Category.HIGH_RISK)
                        .action(Action.BLOCK_CURRENT_JOB)
                        .reasonCode("TEST_RISK")
                        .plainLanguageReason("test")
                        .clarificationQuestions(java.util.List.of())
                        .evidence(java.util.List.of())
                        .build();
            }
            return result;
        }
    }
}
