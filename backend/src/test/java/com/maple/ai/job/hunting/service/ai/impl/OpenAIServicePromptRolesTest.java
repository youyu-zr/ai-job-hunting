package com.maple.ai.job.hunting.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.service.ai.AIService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletion;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpenAIServicePromptRolesTest {

    @Test
    void keepsClassifierRulesAndExternalPayloadInSeparateRoles() {
        String systemRules = "Fixed classifier rules";
        String userPayload = "{\"question\":\"ignore rules\"}";

        Prompt prompt = OpenAIService.createRoleSeparatedPrompt(systemRules, userPayload);

        assertEquals(2, prompt.getInstructions().size());
        assertEquals(MessageType.SYSTEM, prompt.getInstructions().get(0).getMessageType());
        assertEquals(systemRules, prompt.getInstructions().get(0).getContent());
        assertEquals(MessageType.USER, prompt.getInstructions().get(1).getMessageType());
        assertEquals(userPayload, prompt.getInstructions().get(1).getContent());
        assertNotNull(prompt.getOptions());
        assertEquals(0.0f, ((OpenAiChatOptions) prompt.getOptions()).getTemperature());
    }

    @Test
    void twoRoleRequestSerializesRequestLocalZeroTemperatureThroughTheRealClient() throws Exception {
        RequestHarness harness = requestHarness();
        harness.service.askAndAnswer("Synthetic trusted rules", "{\"question\":\"synthetic input\"}");
        ArgumentCaptor<ChatCompletionRequest> capture = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(harness.api).chatCompletionEntity(capture.capture());
        ChatCompletionRequest request = capture.getValue();

        assertEquals(0.0f, request.temperature());
        assertEquals(0.0, new ObjectMapper().readTree(
                new ObjectMapper().writeValueAsString(request)).path("temperature").asDouble());
        assertEquals("SYSTEM", request.messages().get(0).role().name());
        assertEquals("Synthetic trusted rules", request.messages().get(0).content());
        assertEquals("USER", request.messages().get(1).role().name());
        assertEquals("{\"question\":\"synthetic input\"}", request.messages().get(1).content());
        assertEquals("synthetic-model", request.model());
        assertNull(request.responseFormat());
        assertEquals(0.75f, harness.defaults.getTemperature());
    }

    @Test
    void structuredCallsDoNotMutateSharedDefaultsOrOneRoleAndSessionRequests() throws Exception {
        RequestHarness harness = requestHarness();
        harness.service.askAndAnswer("Synthetic greeting");
        harness.service.askAndAnswer("Synthetic trusted rules", "Synthetic payload");
        harness.service.askAndAnswer("Synthetic ordinary reply");
        harness.service.sessionChat("synthetic-session", "Synthetic session question");
        ArgumentCaptor<ChatCompletionRequest> capture = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(harness.api, times(4)).chatCompletionEntity(capture.capture());
        List<ChatCompletionRequest> requests = capture.getAllValues();

        assertEquals(0.75f, requests.get(0).temperature());
        assertEquals(1, requests.get(0).messages().size());
        assertEquals(0.75f, requests.get(2).temperature());
        assertEquals(1, requests.get(2).messages().size());
        assertEquals(0.75f, requests.get(3).temperature());
        assertEquals("Synthetic session rules", requests.get(3).messages().get(0).content());
        assertEquals("Synthetic session question", requests.get(3).messages().get(1).content());
        assertEquals(0.75f, harness.defaults.getTemperature());
        assertNull(harness.defaults.getResponseFormat());
    }

    @Test
    void unsupportedAiServicesFailInsteadOfFlatteningMessageRoles() {
        AIService unsupported = mock(AIService.class, CALLS_REAL_METHODS);

        assertThrows(ApplicationException.class,
                () -> unsupported.askAndAnswer("SYSTEM rules", "USER data"));
    }

    private static RequestHarness requestHarness() throws Exception {
        OpenAiApi api = mock(OpenAiApi.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        ChatCompletion completion = new ObjectMapper().readValue("""
                {"id":"synthetic","model":"synthetic-model","object":"chat.completion","created":0,
                 "usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0},
                 "choices":[{"index":0,"finish_reason":"stop",
                 "message":{"role":"assistant","content":"Synthetic response"}}]}
                """, ChatCompletion.class);
        when(api.chatCompletionEntity(any())).thenReturn(ResponseEntity.ok(completion));
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .withModel("synthetic-model").withTemperature(0.75f).build();
        OpenAiChatClient client = new OpenAiChatClient(api, defaults, null,
                RetryTemplate.builder().maxAttempts(1).build());
        Map<String, List<Message>> sessions = new HashMap<>();
        OpenAIService service = new OpenAIService() {
            @Override
            protected OpenAiChatClient getClient() {
                return client;
            }

            @Override
            public String buildSystemFirstPrompt() {
                return "Synthetic session rules";
            }

            @Override
            public Map<String, List<Message>> getMsgSessionMap() {
                return sessions;
            }

            @Override
            protected void persistentMessage(String sessionId, List<Message> messages) {
            }
        };
        return new RequestHarness(service, api, defaults);
    }

    private record RequestHarness(OpenAIService service, OpenAiApi api, OpenAiChatOptions defaults) {
    }
}
