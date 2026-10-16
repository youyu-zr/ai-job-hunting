package com.maple.ai.job.hunting.service.biz;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.emums.AiSeatStatusEnum;
import com.maple.ai.job.hunting.emums.MsgSessionStatusEnum;
import com.maple.ai.job.hunting.entity.MsgSessionDO;
import com.maple.ai.job.hunting.frame.cache.AiJobGateStopCache;
import com.maple.ai.job.hunting.mapper.MsgSessionMapper;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.ai.impl.OpenAIService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MsgSessionServiceKeyTest {

    @Test
    void manualPauseHasAnExplicitPersistentSessionStatus() {
        assertEquals(3, MsgSessionStatusEnum.MANUALLY_PAUSED.getCode());
        assertEquals(MsgSessionStatusEnum.MANUALLY_PAUSED,
                MsgSessionStatusEnum.getByCode(3));
        assertEquals(11, MsgSessionStatusEnum.encode(2L, MsgSessionStatusEnum.MANUALLY_PAUSED));
        assertEquals(2L, MsgSessionStatusEnum.revision(11));
        assertTrue(MsgSessionStatusEnum.is(11, MsgSessionStatusEnum.MANUALLY_PAUSED));
    }

    @Test
    void manualStopCacheIsScopedByUserAndJob() {
        assertEquals("42:岗位A", MsgSessionService.scopedStopCacheKey(42L, "岗位A"));
        assertNotEquals(
                MsgSessionService.scopedStopCacheKey(42L, "岗位A"),
                MsgSessionService.scopedStopCacheKey(43L, "岗位A")
        );
        assertNotEquals(
                MsgSessionService.scopedStopCacheKey(42L, "岗位A"),
                MsgSessionService.scopedStopCacheKey(42L, "岗位B")
        );
    }

    @Test
    void messageSessionMapperCanFindPausedRowsAndUpdateOnlyTheRequestedUserSession() {
        initializeMapperMetadata();
        MsgSessionMapper mapper = mock(MsgSessionMapper.class, CALLS_REAL_METHODS);
        AtomicReference<LambdaQueryWrapper<MsgSessionDO>> queryRef = new AtomicReference<>();
        AtomicReference<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MsgSessionDO>> updateRef =
                new AtomicReference<>();
        doAnswer(invocation -> {
            queryRef.set((LambdaQueryWrapper<MsgSessionDO>) invocation.getArgument(0));
            return null;
        }).when(mapper).selectOne(any());
        doAnswer(invocation -> {
            updateRef.set((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MsgSessionDO>)
                    invocation.getArgument(1));
            return 1;
        }).when(mapper).update(any(MsgSessionDO.class), any());

        mapper.selectLatestByUserAndSessionKey(42L, "job-1");
        mapper.updateSessionStatus(7L, 42L, MsgSessionStatusEnum.IN_USE,
                MsgSessionStatusEnum.MANUALLY_PAUSED);

        String updateCondition = updateRef.get().getSqlSegment();
        assertTrue(queryRef.get().getSqlSegment().contains("session_key"));
        assertTrue(queryRef.get().getSqlSegment().contains("user_id"));
        assertTrue(queryRef.get().getSqlSegment().contains("MOD(status, 4)"));
        assertTrue(updateCondition.contains("MOD(status, 4)"));
        assertTrue(queryRef.get().getParamNameValuePairs().values().stream()
                .filter(value -> value instanceof Number)
                .map(Number.class::cast)
                .map(Number::longValue)
                .filter(value -> value == 42L)
                .findFirst().isPresent());
        assertTrue(updateRef.get().getSqlSet().contains("FLOOR(status / 4)"));
        assertTrue(updateRef.get().getParamNameValuePairs().values()
                .contains(MsgSessionStatusEnum.IN_USE.getCode()));
    }

    @Test
    void normalContextPersistenceDoesNotChangeTheEncodedPauseRevision() {
        initializeMapperMetadata();
        MsgSessionMapper mapper = mock(MsgSessionMapper.class, CALLS_REAL_METHODS);
        AtomicReference<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MsgSessionDO>>
                updateRef = new AtomicReference<>();
        doAnswer(invocation -> {
            updateRef.set((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MsgSessionDO>)
                    invocation.getArgument(1));
            return 1;
        }).when(mapper).update(any(MsgSessionDO.class), any());

        int statusBeforePersistence = MsgSessionStatusEnum.encode(6L, MsgSessionStatusEnum.MANUALLY_PAUSED);
        mapper.updateMsgContextAndAiType("7", "[]",
                com.maple.ai.job.hunting.emums.AITypeEnum.OPENAI);

        assertTrue(updateRef.get().getSqlSet().contains("msg_context"));
        assertTrue(updateRef.get().getSqlSet().contains("ai_type"));
        assertFalse(updateRef.get().getSqlSet().contains("status"));
        assertEquals(6L, MsgSessionStatusEnum.revision(statusBeforePersistence));
    }

    @Test
    void cachedPauseRemovalDoesNotResumePersistedManualPauseAfterServiceRecreation() {
        initializeMapperMetadata();
        HeaderContext.setHeader(header(42L));
        try {
            MsgSessionDO paused = session(7L, 42L, "job-1",
                    MsgSessionStatusEnum.encode(4L, MsgSessionStatusEnum.MANUALLY_PAUSED));
            MsgSessionMapper mapper = mock(MsgSessionMapper.class, CALLS_REAL_METHODS);
            doReturn(paused).when(mapper).selectLatestByUserAndSessionKey(42L, "job-1");
            UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
            UserInfoDO user = new UserInfoDO();
            user.setAiSeatStatus(AiSeatStatusEnum.OPEN.getCode());
            when(userInfoMapper.selectById(42L)).thenReturn(user);
            OpenAIService openAIService = new OpenAIService();
            openAIService.getMsgSessionMap().clear();

            MsgSessionService firstService = service(mapper, userInfoMapper, openAIService);
            MsgSessionService restartedService = service(mapper, userInfoMapper, openAIService);

            assertTrue(firstService.msgSessionStop("job-1"));
            assertTrue(restartedService.msgSessionStop("job-1"));
            assertTrue(restartedService.getRuntimeStatus("job-1").isManuallyPaused());
            assertFalse(restartedService.getRuntimeStatus("job-1").isGatePaused());
            assertEquals(4L, restartedService.getRuntimeStatus("job-1").getSessionRevision());
        } finally {
            HeaderContext.clear();
        }
    }


    @Test
    void explicitlyResumingPausedSessionRestoresItsPersistedContextIntoTheAiService() {
        HeaderContext.setHeader(header(42L));
        try {
            MsgSessionDO paused = session(99L, 42L, "job-context",
                    MsgSessionStatusEnum.encode(5L, MsgSessionStatusEnum.MANUALLY_PAUSED));
            paused.setAiType(com.maple.ai.job.hunting.emums.AITypeEnum.OPENAI.getCode());
            paused.setMsgContext("""
                    [{"role":"assistant","content":"已确认的项目经历"}]
                    """);
            MsgSessionMapper mapper = mock(MsgSessionMapper.class);
            doReturn(paused).when(mapper).selectLatestByUserAndSessionKey(42L, "job-context");
            doReturn(1).when(mapper).updateSessionStatus(99L, 42L,
                    MsgSessionStatusEnum.MANUALLY_PAUSED, MsgSessionStatusEnum.IN_USE);
            OpenAIService openAIService = new OpenAIService();
            MsgSessionService service = service(mapper, mock(UserInfoMapper.class), openAIService);

            assertTrue(service.setMsgSessionStop("job-context", false));

            assertEquals("已确认的项目经历",
                    openAIService.getMsgSessionMap().get("99").get(0).getContent());
            assertEquals(6L, MsgSessionStatusEnum.revision(paused.getStatus()));
        } finally {
            HeaderContext.clear();
        }
    }

    @Test
    void resumingRetiresOtherRowsOnlyForAuthenticatedUserAndCurrentJob() {
        initializeMapperMetadata();
        HeaderContext.setHeader(header(42L));
        try {
            MsgSessionDO paused = session(99L, 42L, "job-duplicate",
                    MsgSessionStatusEnum.encode(1L, MsgSessionStatusEnum.MANUALLY_PAUSED));
            MsgSessionMapper mapper = mock(MsgSessionMapper.class, CALLS_REAL_METHODS);
            doReturn(paused).when(mapper).selectLatestByUserAndSessionKey(42L, "job-duplicate");
            doReturn(1).when(mapper).updateSessionStatus(99L, 42L,
                    MsgSessionStatusEnum.MANUALLY_PAUSED, MsgSessionStatusEnum.IN_USE);
            AtomicReference<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MsgSessionDO>>
                    retirement = new AtomicReference<>();
            doAnswer(invocation -> {
                retirement.set(invocation.getArgument(1));
                return 1;
            }).when(mapper).update(any(MsgSessionDO.class), any());
            MsgSessionService service = service(mapper, mock(UserInfoMapper.class), new OpenAIService());

            assertTrue(service.setMsgSessionStop("job-duplicate", false));

            assertTrue(retirement.get() != null, "resume must retire competing rows before accepting new turns");
            String condition = retirement.get().getSqlSegment();
            assertTrue(condition.contains("user_id"));
            assertTrue(condition.contains("session_key"));
            assertTrue(condition.contains("id <>"));
            assertTrue(condition.contains("MOD(status, 4)"));
            assertTrue(retirement.get().getParamNameValuePairs().containsValue(42L));
            assertTrue(retirement.get().getParamNameValuePairs().containsValue("job-duplicate"));
            assertTrue(retirement.get().getParamNameValuePairs().containsValue(99L));
            assertFalse(retirement.get().getSqlSet().contains("msg_context"));
        } finally {
            HeaderContext.clear();
        }
    }

    @Test
    void stoppingBeforeAnAiSessionExistsPersistsAPausedPlaceholderInsteadOfUsingMemoryOnlyCache() {
        initializeMapperMetadata();
        HeaderContext.setHeader(header(42L));
        try {
            MsgSessionMapper mapper = mock(MsgSessionMapper.class, CALLS_REAL_METHODS);
            doReturn(null).when(mapper).selectLatestByUserAndSessionKey(42L, "job-new");
            AtomicReference<MsgSessionDO> insertedRef = new AtomicReference<>();
            doAnswer(invocation -> {
                insertedRef.set(invocation.getArgument(0));
                MsgSessionDO inserted = insertedRef.get();
                inserted.setId(100L);
                return 1;
            }).when(mapper).insert(any(MsgSessionDO.class));
            UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
            UserInfoDO user = new UserInfoDO();
            user.setAiSeatStatus(AiSeatStatusEnum.OPEN.getCode());
            when(userInfoMapper.selectById(42L)).thenReturn(user);
            OpenAIService openAIService = new OpenAIService();
            MsgSessionService service = service(mapper, userInfoMapper, openAIService);

            assertTrue(service.setMsgSessionStop("job-new", true));

            MsgSessionDO placeholder = insertedRef.get();
            assertTrue(placeholder != null);
            assertEquals(42L, placeholder.getUserId());
            assertEquals("job-new", placeholder.getSessionKey());
            assertEquals(MsgSessionStatusEnum.encode(0L, MsgSessionStatusEnum.MANUALLY_PAUSED),
                    placeholder.getStatus());
            assertEquals("", placeholder.getMsgContext());
        } finally {
            HeaderContext.clear();
        }
    }

    @Test
    void enablingSeatGateDoesNotClearPersistedManualPause() {
        initializeMapperMetadata();
        HeaderContext.setHeader(header(42L));
        try {
            MsgSessionMapper mapper = mock(MsgSessionMapper.class);
            UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
            OpenAIService openAIService = new OpenAIService();
            MsgSessionService service = service(mapper, userInfoMapper, openAIService);

            assertTrue(service.resumeUserAiJobGates(42L));

            verify(mapper, never()).update(any(MsgSessionDO.class), any());
        } finally {
            HeaderContext.clear();
        }
    }

    @Test
    void runtimeStatusReadsGlobalRevisionFromTheUserPreferenceJson() {
        initializeMapperMetadata();
        HeaderContext.setHeader(header(42L));
        try {
            MsgSessionMapper mapper = mock(MsgSessionMapper.class);
            when(mapper.selectLatestByUserAndSessionKey(42L, "job-revision")).thenReturn(null);
            UserInfoMapper userInfoMapper = mock(UserInfoMapper.class);
            UserInfoDO user = new UserInfoDO();
            user.setAiSeatStatus(AiSeatStatusEnum.OPEN.getCode());
            when(userInfoMapper.selectById(42L)).thenReturn(user);
            PreferenceEntity preference = new PreferenceEntity();
            preference.setAiSeatRevision(17L);
            when(userInfoMapper.selectPreferenceOptByUserId(42L))
                    .thenReturn(java.util.Optional.of(preference));
            OpenAIService openAIService = new OpenAIService();
            MsgSessionService service = service(mapper, userInfoMapper, openAIService);

            assertEquals(17L, service.getRuntimeStatus("job-revision").getGlobalRevision());
        } finally {
            HeaderContext.clear();
        }
    }

    private static MsgSessionService service(MsgSessionMapper mapper, UserInfoMapper userInfoMapper,
                                             OpenAIService openAIService) {
        openAIService.getMsgSessionMap().clear();
        MsgSessionService service = new MsgSessionService(
                new AiJobGateStopCache(), mapper, userInfoMapper);
        ReflectionTestUtils.setField(service, "openAIService", openAIService);
        return service;
    }

    private static Header header(Long userId) {
        UserInfoVO user = new UserInfoVO();
        user.setId(userId);
        return new Header(user, "request", "/api/job/seeker/cloned/session/status",
                null, false, null, null);
    }

    private static MsgSessionDO session(Long id, Long userId, String sessionKey,
                                        int status) {
        MsgSessionDO session = new MsgSessionDO();
        session.setId(id);
        session.setUserId(userId);
        session.setSessionKey(sessionKey);
        session.setStatus(status);
        return session;
    }

    private static void initializeMapperMetadata() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "msg-session-pause-test"),
                MsgSessionDO.class);
    }
}
