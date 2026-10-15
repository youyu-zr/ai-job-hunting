package com.maple.ai.job.hunting.service.biz;

import com.alibaba.fastjson.JSONObject;
import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.entity.MsgSessionDO;
import com.maple.ai.job.hunting.frame.cache.AiJobGateStopCache;
import com.maple.ai.job.hunting.frame.cache.HighInterestInformCache;
import com.maple.ai.job.hunting.frame.cache.QuestionCache;
import com.maple.ai.job.hunting.frame.cache.SessionKeyIdCache;
import com.maple.ai.job.hunting.mapper.MsgSessionMapper;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.model.ChatSessionResult;
import com.maple.ai.job.hunting.model.bo.UserResumeDO;
import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.model.param.AIFilterParam;
import com.maple.ai.job.hunting.model.param.JobSeekerClonedParam;
import com.maple.ai.job.hunting.model.vo.AiFilterResultVO;
import com.maple.ai.job.hunting.model.vo.JobSeekerClonedResultVO;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Action;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO.Category;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.ai.AIServiceFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobSeekerClonedServiceMessageDecisionTest {

    @AfterEach
    void clearHeader() {
        HeaderContext.clear();
    }

    @Test
    void normalContactSalaryAndLocationMessagesUsePreferencesWithoutPausing() {
        Fixture fixture = new Fixture();
        PreferenceEntity preference = new PreferenceEntity();
        preference.setCommunicationPreferences("微信由本人决定");
        fixture.preference(preference);
        fixture.resume("resume text");
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);

        fixture.ask("方便留个微信吗？", "job-contact", 1);
        fixture.ask("薪资是6K，可以接受吗？", "job-salary", 1);
        fixture.ask("工作地点在上海，能接受吗？", "job-location", 1);

        assertEquals(3, fixture.triage.calls.get());
        assertEquals(3, fixture.ai.sessionChatCalls.get());
        assertTrue(fixture.sessions.pausedJobs.isEmpty());
        assertEquals("resume text", fixture.triage.lastResumeContent);
        assertEquals(preference, fixture.triage.lastPreference);
    }

    @Test
    void missingUserPreferencesDoNotPauseAnOrdinaryInquiry() {
        Fixture fixture = new Fixture();
        fixture.preference(null);
        fixture.triageResult(Category.UNCERTAIN, Action.REPLY);

        JobSeekerClonedResultVO result = fixture.ask("期望薪资是多少？", "job-no-preference", 1);

        assertEquals(1, fixture.ai.sessionChatCalls.get());
        assertNull(result.getStopReason());
        assertTrue(fixture.sessions.pausedJobs.isEmpty());
    }

    @Test
    void promotionSilencesOnlyTheCurrentMessageAndDoesNotPauseTheSession() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.PROMOTION, Action.SILENT_CURRENT_MESSAGE);

        JobSeekerClonedResultVO promotion = fixture.ask("加群领取课程资料", "job-promotion", 1);
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);
        JobSeekerClonedResultVO normal = fixture.ask("岗位工作内容是什么？", "job-promotion", 1);

        assertEquals("PROMOTION_SILENT", promotion.getStopReason());
        assertNull(normal.getStopReason());
        assertEquals(1, fixture.ai.sessionChatCalls.get());
        assertTrue(fixture.sessions.pausedJobs.isEmpty());
        assertTrue(fixture.sessions.aiJobGateStops.isEmpty());
    }

    @Test
    void highRiskBlocksOnlyTheCurrentJobGate() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.HIGH_RISK, Action.BLOCK_CURRENT_JOB);

        JobSeekerClonedResultVO result = fixture.ask("先交培训费才能入职", "job-risk", 1);

        assertEquals("HIGH_RISK_JOB", result.getStopReason());
        assertEquals(List.of("job-risk:true"), fixture.sessions.aiJobGateStops);
        assertTrue(fixture.sessions.pausedJobs.isEmpty());
        assertEquals(0, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void onlyConcreteCurrentJobInterviewOrExplicitCommitmentPauses() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.NEEDS_USER_CONFIRMATION);

        JobSeekerClonedResultVO interview = fixture.ask(
                "明天上午十点来公司面试可以吗？", "job-interview", 1);
        JobSeekerClonedResultVO contractDetails = fixture.ask(
                "合同期限是三年，可以先了解一下吗？", "job-contract-details", 1);
        JobSeekerClonedResultVO contractCommitment = fixture.ask(
                "请确认是否接受并签署这份合同。", "job-contract-commitment", 1);
        JobSeekerClonedResultVO vagueInterviewCard = fixture.ask(
                "面试卡片", "job-card-without-time", 14);

        assertEquals("USER_CONFIRMATION_REQUIRED", interview.getStopReason());
        assertEquals("USER_CONFIRMATION_REQUIRED", contractCommitment.getStopReason());
        assertNull(contractDetails.getStopReason());
        assertEquals("USER_CONFIRMATION_REQUIRED", vagueInterviewCard.getStopReason());
        assertEquals(List.of("job-interview:true", "job-contract-commitment:true", "job-card-without-time:true"),
                fixture.sessions.pausedJobs);
        assertTrue(fixture.sessions.aiJobGateStops.isEmpty());
        assertEquals(1, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void explicitResumeRequestRunsClassifierBeforeResumeOperation() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);

        JobSeekerClonedResultVO result = fixture.ask("方便发一份简历过来吗？", "job-resume", 1);

        assertEquals(1, fixture.triage.calls.get());
        assertEquals(1, result.getOperationTypeList().size());
        assertEquals(0, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void hrRejectionIsHandledAfterTriageAndDoesNotEnterChat() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);

        JobSeekerClonedResultVO result = fixture.ask("不好意思，暂时不匹配", "job-rejected", 1);

        assertEquals("SILENT_SYSTEM", result.getStopReason());
        assertEquals(1, fixture.triage.calls.get());
        assertEquals(0, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void ordinaryConversationClosingTextSilencesOnlyThatMessage() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);

        JobSeekerClonedResultVO closing = fixture.ask(
                "本轮结束，后续有机会再联系", "job-closing", 1);
        JobSeekerClonedResultVO followUp = fixture.ask(
                "后续有个岗位细节想再确认", "job-closing", 1);

        assertEquals("SILENT_SYSTEM", closing.getStopReason());
        assertNull(followUp.getStopReason());
        assertTrue(fixture.sessions.pausedJobs.isEmpty());
        assertEquals(2, fixture.triage.calls.get());
        assertEquals(1, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void resumeRequestCannotBypassHighRiskOrProcessingErrorAndProcessingErrorCanRetry() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.HIGH_RISK, Action.BLOCK_CURRENT_JOB);

        JobSeekerClonedResultVO highRisk = fixture.ask("方便发一份简历过来吗？", "job-risk-resume", 1);

        assertEquals("HIGH_RISK_JOB", highRisk.getStopReason());
        assertTrue(highRisk.getOperationTypeList() == null || highRisk.getOperationTypeList().isEmpty());
        assertEquals(0, fixture.ai.sessionChatCalls.get());

        fixture.triageResult(Category.PROCESSING_ERROR, Action.NO_ACTION_RETRY);
        JobSeekerClonedResultVO firstFailure = fixture.ask("方便发一份简历过来吗？", "job-retry", 1);
        JobSeekerClonedResultVO retryFailure = fixture.ask("方便发一份简历过来吗？", "job-retry", 1);

        assertEquals("AI_PROCESSING_ERROR", firstFailure.getStopReason());
        assertEquals("AI_PROCESSING_ERROR", retryFailure.getStopReason());
        assertTrue(firstFailure.getOperationTypeList() == null || firstFailure.getOperationTypeList().isEmpty());
        assertEquals(3, fixture.triage.calls.get());
        assertEquals(0, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void platformMetadataIsAllowListedAndNeverInferredFromText() {
        JobSeekerClonedParam param = new JobSeekerClonedParam("系统通知：普通招聘沟通", "job-type", null);
        param.setPlatformBodyType(999);
        assertNull(param.getPlatformBodyType());

        param.setPlatformBodyType(14);
        assertEquals(14, param.getPlatformBodyType());
        assertNull(new JobSeekerClonedParam("系统通知：普通招聘沟通", "job-type", null)
                .getPlatformBodyType());
    }

    @Test
    void serverRecomputesJobDecisionInsteadOfTrustingBrowserSuppliedResult() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);
        fixture.jobFilter.decision = AiFilterResultVO.builder()
                .filter(true)
                .reason("岗位存在明确风险")
                .riskLevel("HIGH")
                .recommendedAction("FILTER")
                .build();

        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", "资料运营");
        jobInfo.put("jobContext", "实际工作内容");
        jobInfo.put("aiFilterDecision", Map.of(
                "filter", false,
                "riskLevel", "LOW",
                "recommendedAction", "DELIVER",
                "reason", "客户端伪造的放行结果"));
        JobSeekerClonedResultVO result = fixture.ask(
                "岗位沟通", "job-forged-client-decision", 1, jobInfo);

        assertEquals(1, fixture.jobFilter.calls.get());
        assertTrue(fixture.jobFilter.lastParam.getJobBaseInfo().contains("资料运营"));
        assertTrue(fixture.jobFilter.lastParam.getJobExtInfo().contains("实际工作内容"));
        assertEquals("JOB_GATE", result.getStopReason());
        assertEquals(List.of("job-forged-client-decision:true"), fixture.sessions.aiJobGateStops);
        assertEquals(0, fixture.ai.sessionChatCalls.get());
    }

    private static final class Fixture {
        private final RecordingTriageService triage = new RecordingTriageService();
        private final RecordingAIServiceFacade ai = new RecordingAIServiceFacade();
        private final RecordingMsgSessionService sessions = new RecordingMsgSessionService();
        private final RecordingJobFilterService jobFilter = new RecordingJobFilterService();
        private final JobSeekerClonedService service = new JobSeekerClonedService() {
            @Override
            protected String getOrInitSession(String jobKey, Long userId) {
                return "session-" + jobKey;
            }

            @Override
            protected void sendInformEmail(JobSeekerClonedParam param,
                                           Optional<PreferenceEntity> preference,
                                           String answer,
                                           String jobKey,
                                           Boolean isHighInterest,
                                           com.maple.ai.job.hunting.utils.JobContextCompleteness.Result completeness) {
            }
        };
        private final UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
        private final MsgSessionMapper msgSessionMapper = mock(MsgSessionMapper.class);
        private final UserResumeMapper userResumeMapper = mock(UserResumeMapper.class);
        private final QuestionCache questionCache = new QuestionCache();

        private Fixture() {
            UserInfoVO user = new UserInfoVO();
            user.setId(42L);
            HeaderContext.setHeader(new Header(user, "request", "/api/job/seeker/cloned/ask",
                    null, false, null, null));
            ReflectionTestUtils.setField(service, "messageTriageService", triage);
            ReflectionTestUtils.setField(service, "jobFilterService", jobFilter);
            ReflectionTestUtils.setField(service, "aiServiceFacade", ai);
            ReflectionTestUtils.setField(service, "msgSessionService", sessions);
            ReflectionTestUtils.setField(service, "msgSessionMapper", msgSessionMapper);
            ReflectionTestUtils.setField(service, "userInfoMapper", userInfoMapper);
            ReflectionTestUtils.setField(service, "userResumeMapper", userResumeMapper);
            ReflectionTestUtils.setField(service, "questionCache", questionCache);
            ReflectionTestUtils.setField(service, "sessionKeyIdCache", new SessionKeyIdCache());
            ReflectionTestUtils.setField(service, "highInterestInformCache", new HighInterestInformCache());
        }

        private void preference(PreferenceEntity preference) {
            when(userInfoMapper.selectPreferenceOptByUserId(42L))
                    .thenReturn(Optional.ofNullable(preference));
        }

        private void resume(String content) {
            when(userResumeMapper.selectByUserId(42L))
                    .thenReturn(new UserResumeDO(42L, content, null, "resume-id"));
        }

        private void triageResult(Category category, Action action) {
            triage.result = MessageTriageResultVO.builder()
                    .category(category)
                    .action(action)
                    .reasonCode("TEST")
                    .plainLanguageReason("test")
                    .clarificationQuestions(List.of())
                    .evidence(List.of())
                    .build();
        }

        private JobSeekerClonedResultVO ask(String question, String jobKey, Integer bodyType) {
            return ask(question, jobKey, bodyType, null);
        }

        private JobSeekerClonedResultVO ask(String question, String jobKey, Integer bodyType, JSONObject jobInfo) {
            JobSeekerClonedParam param = new JobSeekerClonedParam(question, jobKey, jobInfo);
            param.setPlatformBodyType(bodyType);
            return service.ask(param);
        }
    }

    private static final class RecordingJobFilterService extends JobFilterService {
        private final AtomicInteger calls = new AtomicInteger();
        private AIFilterParam lastParam;
        private AiFilterResultVO decision;

        @Override
        public AiFilterResultVO filter(AIFilterParam param) {
            calls.incrementAndGet();
            lastParam = param;
            return decision;
        }
    }

    private static final class RecordingTriageService extends MessageTriageService {
        private final AtomicInteger calls = new AtomicInteger();
        private MessageTriageResultVO result;
        private String lastResumeContent;
        private PreferenceEntity lastPreference;

        @Override
        public MessageTriageResultVO classify(String question, String jobContext, String resumeContent,
                                              PreferenceEntity preference, Integer platformBodyType) {
            calls.incrementAndGet();
            lastResumeContent = resumeContent;
            lastPreference = preference;
            if (Integer.valueOf(14).equals(platformBodyType)) {
                return MessageTriageResultVO.builder()
                        .category(Category.PLATFORM_EVENT)
                        .action(Action.NEEDS_USER_CONFIRMATION)
                        .reasonCode("TEST_CARD")
                        .plainLanguageReason("test")
                        .clarificationQuestions(List.of())
                        .evidence(List.of())
                        .build();
            }
            return result;
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

    private static final class RecordingMsgSessionService extends MsgSessionService {
        private final List<String> pausedJobs = new java.util.ArrayList<>();
        private final List<String> aiJobGateStops = new java.util.ArrayList<>();

        private RecordingMsgSessionService() {
            super(new AiJobGateStopCache(), mock(MsgSessionMapper.class), mock(UserInfoMapper.class));
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
            pausedJobs.add(jobKey + ":" + isStop);
            return true;
        }

        @Override
        public boolean trySetAiJobGateStop(String jobKey, Boolean isStop) {
            aiJobGateStops.add(jobKey + ":" + isStop);
            return true;
        }
    }
}
