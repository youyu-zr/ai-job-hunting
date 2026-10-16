package com.maple.ai.job.hunting.service.ai.impl;

import com.maple.ai.job.hunting.emums.AITypeEnum;
import com.maple.ai.job.hunting.emums.MsgSessionStatusEnum;
import com.maple.ai.job.hunting.entity.MsgSessionDO;
import com.maple.ai.job.hunting.model.openai.OpenaiMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIServiceSessionPromptTest {

    @AfterEach
    @SuppressWarnings("unchecked")
    void clearStaticSessionMap() {
        ((java.util.Map<String, List<Message>>) ReflectionTestUtils
                .getField(OpenAIService.class, "SESSION_MAP")).clear();
    }

    @Test
    void currentUserMessageIsPresentInThePromptSnapshot() {
        List<Message> messages = new ArrayList<>();
        messages.add(new OpenaiMessage(MessageType.SYSTEM, "system"));
        messages.add(new OpenaiMessage(MessageType.ASSISTANT, "上一轮回复"));
        messages.add(new OpenaiMessage(MessageType.USER, "本轮HR消息"));

        List<Message> promptMessages = OpenAIService.buildPromptMessages(messages);

        assertEquals(3, promptMessages.size());
        assertTrue(promptMessages.stream().anyMatch(message -> "本轮HR消息".equals(message.getContent())));
    }

    @Test
    void resumingManuallyPausedSessionRestoresItsPersistedConversationContext() {
        MsgSessionDO pausedSession = new MsgSessionDO();
        pausedSession.setId(99L);
        pausedSession.setStatus(MsgSessionStatusEnum.MANUALLY_PAUSED.getCode());
        pausedSession.setAiType(AITypeEnum.OPENAI.getCode());
        pausedSession.setMsgContext("""
                [{"role":"system","content":"旧系统提示词"},
                 {"role":"assistant","content":"暂停前已经确认的经历"}]
                """);
        OpenAIService service = new OpenAIService();

        service.restoreSession(pausedSession);

        List<Message> restored = service.getMsgSessionMap().get("99");
        assertEquals(2, restored.size());
        assertEquals("暂停前已经确认的经历", restored.get(1).getContent());
        assertEquals(AITypeEnum.OPENAI.getCode(), pausedSession.getAiType());
    }
}
