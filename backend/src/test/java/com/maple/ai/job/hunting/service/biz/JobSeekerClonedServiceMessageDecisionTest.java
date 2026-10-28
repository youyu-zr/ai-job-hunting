package com.maple.ai.job.hunting.service.biz;

import com.alibaba.fastjson.JSONObject;
import com.maple.ai.job.hunting.utils.StopEventPolicy;
import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.entity.MsgSessionDO;
import com.maple.ai.job.hunting.emums.JobSeekerClonedAnswerTypeEnum;
import com.maple.ai.job.hunting.frame.cache.AiJobGateStopCache;
import com.maple.ai.job.hunting.frame.cache.HighInterestInformCache;
import com.maple.ai.job.hunting.frame.cache.QuestionCache;
import com.maple.ai.job.hunting.frame.cache.SessionKeyIdCache;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobSeekerClonedServiceMessageDecisionTest {

    @AfterEach
    void clearHeader() {
        HeaderContext.clear();
    }

    @Test
    void byDefaultContactAndSalaryDecisionsPauseWithAHoldReplyWhileOrdinaryQuestionsReachAi() {
        Fixture fixture = new Fixture();
        fixture.preference(new PreferenceEntity());
        fixture.resume("resume text");
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);

        JobSeekerClonedResultVO contact = fixture.ask("方便留个微信吗？", "job-contact", 1);
        JobSeekerClonedResultVO salary = fixture.ask("薪资是6K，可以接受吗？", "job-salary", 1);
        fixture.ask("工作地点在上海，能接受吗？", "job-location", 1);

        assertEquals("CONTACT", contact.getHandoffEvent());
        assertEquals("SALARY_DECISION", salary.getHandoffEvent());
        assertEquals("USER_CONFIRMATION_REQUIRED", salary.getStopReason());
        assertEquals("收到，我确认一下，尽快回复您。", salary.getAnswerContent());
        assertEquals(List.of("job-contact:true", "job-salary:true"), fixture.sessions.pausedJobs);
        assertEquals(1, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void contactAndSalaryLetAiHandleThemWhenTheUserChoseAllow() {
        Fixture fixture = new Fixture();
        PreferenceEntity preference = new PreferenceEntity();
        preference.setCommunicationPreferences("微信由本人决定");
        preference.setStopContact("ALLOW");
        preference.setStopSalary("ALLOW");
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
    void anAnswerLeakingAPhoneNumberIsHeldUnlessTheUserAllowsIt() {
        Fixture fixture = new Fixture();
        fixture.preference(new PreferenceEntity());
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);
        fixture.ai.nextAnswer = "可以的，我的电话是 138 0013 8000，随时联系。";

        // 问题本身不是「要联系方式」，所以走到 AI；是 AI 自己在回复里写出了号码，由发送前检查拦下。
        JobSeekerClonedResultVO held = fixture.ask("后续怎么联系您比较方便？", "job-phone-held", 1);

        assertEquals("USER_CONFIRMATION_REQUIRED", held.getStopReason());
        assertEquals("CONTACT", held.getHandoffEvent());
        assertFalse(held.getAnswerContent().contains("138"));
        assertEquals(List.of("job-phone-held:true"), fixture.sessions.pausedJobs);

        Fixture allowed = new Fixture();
        PreferenceEntity share = new PreferenceEntity();
        share.setContactShareE(true);
        allowed.preference(share);
        allowed.triageResult(Category.NORMAL_JOB, Action.REPLY);
        allowed.ai.nextAnswer = "可以的，我的电话是 13800138000。";

        JobSeekerClonedResultVO sent = allowed.ask("后续怎么联系您比较方便？", "job-phone-allowed", 1);

        assertEquals("可以的，我的电话是 13800138000。", sent.getAnswerContent());
        assertTrue(allowed.sessions.pausedJobs.isEmpty());
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
    void identicalInterviewCardAfterAnOrdinaryTextStillRequiresConfirmation() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);
        String preview = "Interview invitation";

        JobSeekerClonedResultVO text = fixture.ask(preview, "job-card-after-text", 1);
        JobSeekerClonedResultVO card = fixture.ask(preview, "job-card-after-text", 14);

        assertNull(text.getStopReason());
        assertEquals("USER_CONFIRMATION_REQUIRED", card.getStopReason());
        assertEquals(List.of("job-card-after-text:true"), fixture.sessions.pausedJobs);
        assertTrue(card.getOperationTypeList() == null || card.getOperationTypeList().isEmpty());
        assertEquals(1, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void interviewCardAfterExplicitResumeCannotBeSilencedByTextDeduplication() {
        Fixture fixture = new Fixture();
        AtomicBoolean paused = new AtomicBoolean();
        MsgSessionService sessions = mock(MsgSessionService.class);
        when(sessions.msgSessionStop("job-resumed-card")).thenAnswer(invocation -> paused.get());
        when(sessions.trySetMsgSessionStop("job-resumed-card", true)).thenAnswer(invocation -> {
            paused.set(true);
            return true;
        });
        ReflectionTestUtils.setField(fixture.service, "msgSessionService", sessions);

        assertEquals("USER_CONFIRMATION_REQUIRED",
                fixture.ask("Interview invitation", "job-resumed-card", 14).getStopReason());
        assertEquals("SESSION_PAUSED",
                fixture.ask("Interview invitation", "job-resumed-card", 14).getStopReason());
        paused.set(false);
        assertEquals("USER_CONFIRMATION_REQUIRED",
                fixture.ask("Interview invitation", "job-resumed-card", 14).getStopReason());
        assertTrue(paused.get());
        assertEquals(0, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void legacyAndTypedOrdinaryTextStillShareTheSameDeduplicationIdentity() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);

        JobSeekerClonedResultVO first = fixture.ask("Please describe the job", "job-text-identity", null);
        JobSeekerClonedResultVO repeated = fixture.ask("Please describe the job", "job-text-identity", 1);

        // 补扫重发同一条消息：拿回第一次的结果，不再调模型，也不会被当成「已处理」吞掉。
        assertSame(first, repeated);
        assertEquals(1, fixture.ai.sessionChatCalls.get());
        assertTrue(fixture.sessions.pausedJobs.isEmpty());
    }

    @Test
    void aRepeatWhileTheFirstIsStillGeneratingIsRetryableNotSwallowed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        fixture.ai.beforeSessionChat = () -> {
            entered.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<JobSeekerClonedResultVO> slow =
                    pool.submit(() -> fixture.ask("What is the salary", "job-slow", 1));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));

            JobSeekerClonedResultVO repeat = fixture.ask("What is the salary", "job-slow", 1);
            assertEquals("IN_PROGRESS", repeat.getStopReason());
            assertTrue(repeat.isRetryable());

            release.countDown();
            JobSeekerClonedResultVO done = slow.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertSame(done, fixture.ask("What is the salary", "job-slow", 1));
            assertEquals(1, fixture.ai.sessionChatCalls.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
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
    void savedMarketingOptInAllowsPromotionReplyToReachChat() {
        Fixture fixture = new Fixture();
        PreferenceEntity preference = new PreferenceEntity();
        preference.setMarketingReplyE(true);
        fixture.preference(preference);
        fixture.triageResult(Category.PROMOTION, Action.REPLY);

        JobSeekerClonedResultVO result = fixture.ask(
                "Could you explain the brand promotion duties?", "job-opt-in", 1);

        assertNull(result.getStopReason());
        assertEquals(List.of(JobSeekerClonedAnswerTypeEnum.MSG_TEXT.getCode()), result.getAnswerTypeList());
        assertFalse(result.getAnswerContent().isBlank());
        assertEquals(1, fixture.ai.sessionChatCalls.get());
        assertEquals(preference, fixture.triage.lastPreference);
        assertTrue(fixture.sessions.pausedJobs.isEmpty());
        assertTrue(fixture.sessions.aiJobGateStops.isEmpty());
    }

    @Test
    void promotionReplyWithoutSavedOptInRemainsSilent() {
        for (PreferenceEntity preference : new PreferenceEntity[]{null, new PreferenceEntity()}) {
            Fixture fixture = new Fixture();
            fixture.preference(preference);
            fixture.triageResult(Category.PROMOTION, Action.REPLY);
            JSONObject jobInfo = new JSONObject();
            jobInfo.put("marketingReplyE", true);

            JobSeekerClonedResultVO result = fixture.ask(
                    "Could you explain the brand promotion duties?", "job-no-opt-in", 1, jobInfo);

            assertEquals("PROMOTION_SILENT", result.getStopReason());
            assertEquals(List.of(JobSeekerClonedAnswerTypeEnum.STOP.getCode()), result.getAnswerTypeList());
            assertTrue(result.getOperationTypeList() == null || result.getOperationTypeList().isEmpty());
            assertEquals(0, fixture.ai.sessionChatCalls.get());
            assertTrue(fixture.sessions.pausedJobs.isEmpty());
            assertTrue(fixture.sessions.aiJobGateStops.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, mode = EnumSource.Mode.EXCLUDE, names = "REPLY")
    @NullSource
    void marketingOptInNeverTurnsNonReplyOrInvalidActionsIntoChat(Action action) {
        Fixture fixture = new Fixture();
        PreferenceEntity preference = new PreferenceEntity();
        preference.setMarketingReplyE(true);
        fixture.preference(preference);
        fixture.triageResult(Category.PROMOTION, action);

        JobSeekerClonedResultVO result = fixture.ask(
                "方便发一份简历过来吗？", "job-non-reply", 1);

        String expectedReason = action == null ? "AI_PROCESSING_ERROR" : switch (action) {
            case SILENT_CURRENT_MESSAGE -> "PROMOTION_SILENT";
            case BLOCK_CURRENT_JOB -> "HIGH_RISK_JOB";
            case NEEDS_USER_CONFIRMATION -> "USER_CONFIRMATION_REQUIRED";
            default -> "AI_PROCESSING_ERROR";
        };
        assertEquals(expectedReason, result.getStopReason());
        assertEquals(List.of(JobSeekerClonedAnswerTypeEnum.STOP.getCode()), result.getAnswerTypeList());
        assertTrue(result.getOperationTypeList() == null || result.getOperationTypeList().isEmpty());
        assertEquals(0, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void marketingOptInCannotOverrideHighRiskCategoryWithConflictingReplyAction() {
        Fixture fixture = new Fixture();
        PreferenceEntity preference = new PreferenceEntity();
        preference.setMarketingReplyE(true);
        fixture.preference(preference);
        fixture.triageResult(Category.HIGH_RISK, Action.REPLY);

        JobSeekerClonedResultVO result = fixture.ask(
                "Please send your resume and pay an upfront fee.", "job-risk-opt-in", 1);

        assertEquals("HIGH_RISK_JOB", result.getStopReason());
        assertEquals(List.of("job-risk-opt-in:true"), fixture.sessions.aiJobGateStops);
        assertTrue(result.getOperationTypeList() == null || result.getOperationTypeList().isEmpty());
        assertEquals(0, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void optedInPromotionReplyStillRunsTheAuthoritativeJobGate() {
        Fixture fixture = new Fixture();
        PreferenceEntity preference = new PreferenceEntity();
        preference.setMarketingReplyE(true);
        fixture.preference(preference);
        fixture.triageResult(Category.PROMOTION, Action.REPLY);
        fixture.jobFilter.decision = AiFilterResultVO.builder()
                .filter(true).recommendedAction("FILTER").riskLevel("HIGH").build();
        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", "Brand promotion");

        JobSeekerClonedResultVO result = fixture.ask(
                "Could you explain the brand promotion duties?", "job-promotion-gate", 1, jobInfo);

        assertEquals("JOB_GATE", result.getStopReason());
        assertEquals(1, fixture.jobFilter.calls.get());
        assertEquals(List.of("job-promotion-gate:true"), fixture.sessions.aiJobGateStops);
        assertEquals(0, fixture.ai.sessionChatCalls.get());
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
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);
        JobSeekerClonedResultVO contractDetails = fixture.ask(
                "合同期限是三年，可以先了解一下吗？", "job-contract-details", 1);
        fixture.triageResult(Category.NORMAL_JOB, Action.NEEDS_USER_CONFIRMATION);
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
    void classifierConfirmationOutsideKeywordVocabularyStopsBeforeChat() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.NEEDS_USER_CONFIRMATION);

        JobSeekerClonedResultVO result = fixture.ask(
                "请回复确认接受这份报价。", "job-quote", 1);

        assertEquals("USER_CONFIRMATION_REQUIRED", result.getStopReason());
        assertEquals(List.of("job-quote:true"), fixture.sessions.pausedJobs);
        assertEquals(0, fixture.ai.sessionChatCalls.get());
        assertTrue(result.getOperationTypeList() == null || result.getOperationTypeList().isEmpty());
    }

    @Test
    void classifierConfirmationStopsBeforeResumeExchange() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.NEEDS_USER_CONFIRMATION);

        JobSeekerClonedResultVO result = fixture.ask(
                "请发一份简历，并回复确认接受这份报价。", "job-quote-resume", 1);

        assertEquals("USER_CONFIRMATION_REQUIRED", result.getStopReason());
        assertEquals(List.of("job-quote-resume:true"), fixture.sessions.pausedJobs);
        assertEquals(0, fixture.ai.sessionChatCalls.get());
        assertTrue(result.getOperationTypeList() == null || result.getOperationTypeList().isEmpty());
    }

    @Test
    void failedConfirmationPauseRemainsRetryableWithoutSendingOrConsumingTheMessage() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.NEEDS_USER_CONFIRMATION);
        fixture.sessions.pauseSucceeds = false;

        JobSeekerClonedResultVO failed = fixture.ask(
                "请发一份简历，并回复确认接受这份报价。", "job-pause-retry", 1);
        fixture.sessions.pauseSucceeds = true;
        JobSeekerClonedResultVO recovered = fixture.ask(
                "请发一份简历，并回复确认接受这份报价。", "job-pause-retry", 1);

        assertEquals("AI_PROCESSING_ERROR", failed.getStopReason());
        assertTrue(failed.isRetryable());
        assertTrue(failed.getOperationTypeList() == null || failed.getOperationTypeList().isEmpty());
        assertEquals("USER_CONFIRMATION_REQUIRED", recovered.getStopReason());
        assertEquals(0, fixture.ai.sessionChatCalls.get());
        assertEquals(List.of("job-pause-retry:true", "job-pause-retry:true"), fixture.sessions.pausedJobs);
    }

    @Test
    void jobAnalysisExceptionReleasesReservationSoIdenticalMessageCanRecover() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);
        fixture.jobFilter.failure = new ApplicationException("岗位分析暂时不可用，请稍后重试");
        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", "开发工程师");

        assertThrows(ApplicationException.class,
                () -> fixture.ask("请介绍一下项目经历", "job-analysis-retry", 1, jobInfo));
        fixture.jobFilter.failure = null;
        fixture.jobFilter.decision = AiFilterResultVO.builder()
                .filter(false).recommendedAction("DELIVER").riskLevel("LOW").build();
        JobSeekerClonedResultVO recovered = fixture.ask(
                "请介绍一下项目经历", "job-analysis-retry", 1, jobInfo);

        assertNull(recovered.getStopReason());
        assertEquals(List.of(JobSeekerClonedAnswerTypeEnum.MSG_TEXT.getCode()), recovered.getAnswerTypeList());
        assertEquals(2, fixture.jobFilter.calls.get());
        assertEquals(1, fixture.ai.sessionChatCalls.get());
    }

    @Test
    void passedJobGateIsReusedForLaterMessagesOnTheSameJob() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);
        fixture.jobFilter.decision = AiFilterResultVO.builder()
                .filter(false).recommendedAction("DELIVER").riskLevel("LOW").build();
        JSONObject jobInfo = new JSONObject();
        jobInfo.put("jobTitle", "开发工程师");

        fixture.ask("请介绍一下项目经历", "job-gate-reuse", 1, jobInfo);
        fixture.ask("你们用什么技术栈？", "job-gate-reuse", 1, jobInfo);
        assertEquals(1, fixture.jobFilter.calls.get(), "second message on the same job should not re-run the job AI");

        JSONObject changed = new JSONObject();
        changed.put("jobTitle", "开发工程师");
        changed.put("jobContext", "岗位描述更新：需要先交培训费");
        fixture.ask("还有别的要求吗？", "job-gate-reuse", 1, changed);
        assertEquals(2, fixture.jobFilter.calls.get(), "changed job content must be screened again");

        PreferenceEntity stricter = new PreferenceEntity();
        stricter.setBlockedCompanies(List.of("示例科技"));
        fixture.preference(stricter);
        fixture.ask("什么时候能面试？", "job-gate-reuse", 1, changed);
        assertEquals(3, fixture.jobFilter.calls.get(), "changed preferences must be screened again");
    }

    @Test
    void chatFailureResultReleasesReservationSoIdenticalMessageCanRecover() {
        Fixture fixture = new Fixture();
        fixture.triageResult(Category.NORMAL_JOB, Action.REPLY);
        fixture.ai.failNextChat = true;

        JobSeekerClonedResultVO failed = fixture.ask("请介绍一下项目经历", "job-chat-retry", 1);
        JobSeekerClonedResultVO recovered = fixture.ask("请介绍一下项目经历", "job-chat-retry", 1);

        assertEquals(List.of(JobSeekerClonedAnswerTypeEnum.AI_SERVICE_EXCEPTION.getCode()), failed.getAnswerTypeList());
        assertTrue(failed.getOperationTypeList() == null || failed.getOperationTypeList().isEmpty());
        assertNull(recovered.getStopReason());
        assertEquals(List.of(JobSeekerClonedAnswerTypeEnum.MSG_TEXT.getCode()), recovered.getAnswerTypeList());
        assertEquals(2, fixture.ai.sessionChatCalls.get());
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
    void resumeRequestFollowsTheUsersResumeSetting() {
        Fixture paused = new Fixture();
        PreferenceEntity pause = new PreferenceEntity();
        pause.setStopResume("PAUSE");
        paused.preference(pause);
        paused.triageResult(Category.NORMAL_JOB, Action.REPLY);
        JobSeekerClonedResultVO held = paused.ask("方便发一份简历过来吗？", "job-resume-pause", 1);
        assertEquals("RESUME", held.getHandoffEvent());
        assertTrue(held.getOperationTypeList() == null || held.getOperationTypeList().isEmpty());
        assertEquals(List.of("job-resume-pause:true"), paused.sessions.pausedJobs);

        Fixture declined = new Fixture();
        PreferenceEntity decline = new PreferenceEntity();
        decline.setStopResume("DECLINE");
        declined.preference(decline);
        declined.triageResult(Category.NORMAL_JOB, Action.REPLY);
        JobSeekerClonedResultVO first = declined.ask("方便发一份简历过来吗？", "job-resume-decline", 1);
        assertEquals(StopEventPolicy.declineText(StopEventPolicy.Event.RESUME), first.getAnswerContent());
        assertTrue(declined.sessions.pausedJobs.isEmpty());
        JobSeekerClonedResultVO second = declined.ask("简历发一下吧", "job-resume-decline", 1);
        assertEquals("USER_CONFIRMATION_REQUIRED", second.getStopReason());
        assertEquals(0, declined.ai.sessionChatCalls.get());
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
        private RuntimeException failure;

        @Override
        public AiFilterResultVO filter(AIFilterParam param) {
            calls.incrementAndGet();
            lastParam = param;
            if (failure != null) {
                throw failure;
            }
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
        private boolean failNextChat;
        private Runnable beforeSessionChat;
        private String nextAnswer;

        @Override
        public ChatSessionResult sessionChat(String sessionId, String ask) {
            sessionChatCalls.incrementAndGet();
            if (beforeSessionChat != null) {
                beforeSessionChat.run();
            }
            ChatSessionResult result = new ChatSessionResult();
            if (failNextChat) {
                failNextChat = false;
                result.setAnswerStatus(JobSeekerClonedAnswerTypeEnum.AI_SERVICE_EXCEPTION);
                return result;
            }
            result.setAnswer(nextAnswer != null ? nextAnswer : "好的，我们先了解一下岗位安排。");
            return result;
        }
    }

    private static final class RecordingMsgSessionService extends MsgSessionService {
        private final List<String> pausedJobs = new java.util.ArrayList<>();
        private final List<String> aiJobGateStops = new java.util.ArrayList<>();
        private boolean pauseSucceeds = true;

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
            return pauseSucceeds;
        }

        @Override
        public boolean trySetAiJobGateStop(String jobKey, Boolean isStop) {
            aiJobGateStops.add(jobKey + ":" + isStop);
            return true;
        }
    }
}
