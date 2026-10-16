package com.maple.ai.job.hunting.service.ai;

import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.consts.CommunicationStylePrompt;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.model.AiFileResolveResult;
import com.maple.ai.job.hunting.model.ChatSessionResult;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractAIServicePromptBoundaryTest {

    @Test
    void builtInCommunicationStylesRemainAvailableAfterFreePromptRemoval() {
        assertTrue(CommunicationStylePrompt.resolve("NATURAL").contains("自然稳妥"));
        assertTrue(CommunicationStylePrompt.resolve("PROACTIVE").contains("主动了解"));
    }

    @AfterEach
    void clearRequestContext() {
        HeaderContext.clear();
    }

    @Test
    void missingResumeReturnsAnActionableErrorInsteadOfNullPointer() {
        UserResumeMapper resumeMapper = mock(UserResumeMapper.class);
        when(resumeMapper.selectOne(any())).thenReturn(null);

        AbstractAIService service = new AbstractAIService() {
            @Override
            public ChatSessionResult sessionChat(String sessionId, String ask) {
                return null;
            }

            @Override
            public String askAndAnswer(String ask) {
                return null;
            }

            @Override
            public AiFileResolveResult readFile(InputStream inputStream, String ask) {
                return null;
            }
        };
        ReflectionTestUtils.setField(service, "userResumeMapper", resumeMapper);
        ReflectionTestUtils.setField(service, "userInfoMapper", mock(UserInfoMapper.class));
        HeaderContext.setHeader(new Header(new UserInfoVO(), null, null, null, false, null, null));

        ApplicationException error = assertThrows(ApplicationException.class, service::buildSystemFirstPrompt);

        assertEquals("请先导入简历", error.getMessage());
    }
}
