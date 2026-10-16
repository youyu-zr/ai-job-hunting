package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.emums.AiSeatStatusEnum;
import com.maple.ai.job.hunting.emums.MsgSessionStatusEnum;
import com.maple.ai.job.hunting.entity.MsgSessionDO;
import com.maple.ai.job.hunting.frame.cache.AiJobGateStopCache;
import com.maple.ai.job.hunting.frame.cache.HighInterestInformCache;
import com.maple.ai.job.hunting.frame.cache.QuestionCache;
import com.maple.ai.job.hunting.frame.cache.SessionKeyIdCache;
import com.maple.ai.job.hunting.mapper.MsgSessionMapper;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.model.ChatSessionResult;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.model.param.JobSeekerClonedParam;
import com.maple.ai.job.hunting.model.vo.JobSeekerClonedResultVO;
import com.maple.ai.job.hunting.model.vo.MessageTriageResultVO;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.ai.AIServiceFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobSeekerClonedServiceSessionRecoveryTest {

    @AfterEach
    void clearHeader() {
        HeaderContext.clear();
    }

    @Test
    void pausingExistingSessionDuringClassificationDoesNotCreateAnotherRow() throws Exception {
        assertPauseDuringClassification(false);
    }

    @Test
    void pausingBeforeFirstSessionDuringClassificationKeepsOnlyThePausedPlaceholder() throws Exception {
        assertPauseDuringClassification(true);
    }

    @Test
    void finalSessionLookupDoesNotReplaceAPersistedPausedRow() {
        Fixture fixture = new Fixture();
        fixture.addSession(10L, "job-paused", 7);
        HeaderContext.setHeader(header());

        String sessionId = fixture.service.getOrInitSession("job-paused", 42L);

        assertNull(sessionId);
        assertEquals(1, fixture.rows.size());
        assertEquals(7, fixture.rows.get(0).getStatus());
        assertEquals(0, fixture.chatCalls.get());
    }

    @Test
    void creatingAndReusingASessionKeepsOneActiveRow() {
        Fixture fixture = new Fixture();
        HeaderContext.setHeader(header());

        String first = fixture.service.getOrInitSession("job-new", 42L);
        String second = fixture.service.getOrInitSession("job-new", 42L);

        assertEquals(first, second);
        assertEquals(1, fixture.rows.size());
        assertTrue(MsgSessionStatusEnum.is(fixture.rows.get(0).getStatus(), MsgSessionStatusEnum.IN_USE));
    }

    @Test
    void legacyRunningDuplicatesReuseTheAuthoritativeRowWithoutAnAmbiguousSelect() {
        Fixture fixture = new Fixture();
        fixture.addSession(10L, "job-legacy", 9);
        fixture.addSession(11L, "job-legacy", 1);
        HeaderContext.setHeader(header());

        String sessionId = assertDoesNotThrow(
                () -> fixture.service.getOrInitSession("job-legacy", 42L));

        assertEquals("10", sessionId);
        assertEquals(2, fixture.rows.size());
    }

    private void assertPauseDuringClassification(boolean firstSession) throws Exception {
        Fixture fixture = new Fixture();
        if (!firstSession) {
            fixture.addSession(10L, "job-pending", MsgSessionStatusEnum.IN_USE.getCode());
        }
        fixture.addSession(20L, "job-other", MsgSessionStatusEnum.IN_USE.getCode());
        CountDownLatch classifying = new CountDownLatch(1);
        CountDownLatch finishClassification = new CountDownLatch(1);
        MessageTriageService triage = new MessageTriageService() {
            @Override
            public MessageTriageResultVO classify(String question, String jobContext, String resumeContent,
                                                  PreferenceEntity preference, Integer bodyType) {
                classifying.countDown();
                try {
                    assertTrue(finishClassification.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return normalTriage();
            }
        };
        ReflectionTestUtils.setField(fixture.service, "messageTriageService", triage);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> {
                HeaderContext.setHeader(header());
                try {
                    return fixture.service.ask(new JobSeekerClonedParam(
                            "请介绍一下项目经历", "job-pending", null));
                } finally {
                    HeaderContext.clear();
                }
            });
            assertTrue(classifying.await(5, TimeUnit.SECONDS));
            HeaderContext.setHeader(header());
            assertTrue(fixture.sessions.setMsgSessionStop("job-pending", true));
            finishClassification.countDown();
            JobSeekerClonedResultVO result = pending.get(5, TimeUnit.SECONDS);

            assertEquals(1L, fixture.rows.stream().filter(row -> "job-pending".equals(row.getSessionKey())).count());
            assertEquals("SESSION_PAUSED", result.getStopReason());
            assertEquals(0, fixture.chatCalls.get());
            assertTrue(fixture.sessions.msgSessionStop("job-pending"));
            assertFalse(fixture.sessions.msgSessionStop("job-other"));
            assertTrue(fixture.sessions.setMsgSessionStop("job-pending", false));
            assertFalse(fixture.sessions.msgSessionStop("job-pending"));
            assertEquals(1L, fixture.rows.stream()
                    .filter(row -> "job-pending".equals(row.getSessionKey()))
                    .filter(row -> MsgSessionStatusEnum.is(row.getStatus(), MsgSessionStatusEnum.IN_USE)).count());
        } finally {
            finishClassification.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static MessageTriageResultVO normalTriage() {
        return MessageTriageResultVO.builder()
                .category(MessageTriageResultVO.Category.NORMAL_JOB)
                .action(MessageTriageResultVO.Action.REPLY)
                .build();
    }

    private static Header header() {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        return new Header(user, "request", "/api/job/seeker/cloned/ask", null, false, null, null);
    }

    private static final class Fixture {
        private final List<MsgSessionDO> rows = new CopyOnWriteArrayList<>();
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final MsgSessionMapper mapper = mock(MsgSessionMapper.class);
        private final UserInfoMapper users = mock(UserInfoMapper.class);
        private final MsgSessionService sessions = new MsgSessionService(new AiJobGateStopCache(), mapper, users);
        private final JobSeekerClonedService service = new JobSeekerClonedService() {
            @Override
            protected void sendInformEmail(JobSeekerClonedParam param, Optional<PreferenceEntity> preference,
                                           String answer, String jobKey, Boolean highInterest,
                                           com.maple.ai.job.hunting.utils.JobContextCompleteness.Result completeness) {
            }
        };

        private Fixture() {
            UserInfoDO user = new UserInfoDO();
            user.setAiSeatStatus(AiSeatStatusEnum.OPEN.getCode());
            when(users.selectById(42L)).thenReturn(user);
            when(users.selectByIdForUpdate(42L)).thenReturn(user);
            when(users.selectPreferenceOptByUserId(42L)).thenReturn(Optional.empty());
            when(mapper.selectLatestByUserAndSessionKey(eq(42L), anyString())).thenAnswer(invocation ->
                    rows.stream().filter(row -> invocation.getArgument(1).equals(row.getSessionKey()))
                            .max(Comparator.comparing(MsgSessionDO::getStatus).thenComparing(MsgSessionDO::getId))
                            .map(Fixture::copy).orElse(null));
            when(mapper.selectInUseByUserAndSessionKey(eq(42L), anyString())).thenAnswer(invocation -> {
                var running = rows.stream()
                        .filter(row -> invocation.getArgument(1).equals(row.getSessionKey()))
                        .filter(row -> MsgSessionStatusEnum.is(row.getStatus(), MsgSessionStatusEnum.IN_USE)).toList();
                if (running.size() > 1) {
                    throw new IllegalStateException("multiple active sessions");
                }
                return running.isEmpty() ? null : copy(running.get(0));
            });
            when(mapper.insert(any(MsgSessionDO.class))).thenAnswer(invocation -> {
                MsgSessionDO row = invocation.getArgument(0);
                row.setId(100L + rows.size());
                rows.add(row);
                return 1;
            });
            when(mapper.updateSessionStatus(anyLong(), eq(42L), any(), any())).thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                MsgSessionStatusEnum expected = invocation.getArgument(2);
                MsgSessionStatusEnum target = invocation.getArgument(3);
                for (MsgSessionDO row : rows) {
                    if (id.equals(row.getId()) && MsgSessionStatusEnum.is(row.getStatus(), expected)) {
                        row.setStatus(MsgSessionStatusEnum.encode(
                                MsgSessionStatusEnum.revision(row.getStatus()) + 1L, target));
                        return 1;
                    }
                }
                return 0;
            });
            AIServiceFacade ai = new AIServiceFacade() {
                @Override
                public ChatSessionResult sessionChat(String sessionId, String ask) {
                    chatCalls.incrementAndGet();
                    ChatSessionResult result = new ChatSessionResult();
                    result.setAnswer("Please describe the role.");
                    return result;
                }
            };
            ReflectionTestUtils.setField(service, "msgSessionService", sessions);
            ReflectionTestUtils.setField(service, "msgSessionMapper", mapper);
            ReflectionTestUtils.setField(service, "userInfoMapper", users);
            ReflectionTestUtils.setField(service, "userResumeMapper", mock(UserResumeMapper.class));
            ReflectionTestUtils.setField(service, "aiServiceFacade", ai);
            ReflectionTestUtils.setField(service, "questionCache", new QuestionCache());
            ReflectionTestUtils.setField(service, "sessionKeyIdCache", new SessionKeyIdCache());
            ReflectionTestUtils.setField(service, "highInterestInformCache", new HighInterestInformCache());
        }

        private void addSession(Long id, String key, int status) {
            MsgSessionDO row = new MsgSessionDO();
            row.setId(id);
            row.setUserId(42L);
            row.setSessionKey(key);
            row.setStatus(status);
            rows.add(row);
        }

        private static MsgSessionDO copy(MsgSessionDO row) {
            MsgSessionDO copy = new MsgSessionDO();
            copy.setId(row.getId());
            copy.setUserId(row.getUserId());
            copy.setSessionKey(row.getSessionKey());
            copy.setStatus(row.getStatus());
            return copy;
        }
    }
}
