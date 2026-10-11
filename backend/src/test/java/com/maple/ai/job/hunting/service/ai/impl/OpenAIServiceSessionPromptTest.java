package com.maple.ai.job.hunting.service.ai.impl;

import com.maple.ai.job.hunting.model.openai.OpenaiMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIServiceSessionPromptTest {

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
}
