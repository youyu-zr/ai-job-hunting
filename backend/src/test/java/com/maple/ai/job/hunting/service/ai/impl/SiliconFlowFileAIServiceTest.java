package com.maple.ai.job.hunting.service.ai.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiliconFlowFileAIServiceTest {

    @Test
    void textRequestUsesTheConfiguredDeepSeekModel() {
        JSONObject request = SiliconFlowFileAIService.buildTextRequest(
                "deepseek-ai/DeepSeek-V3.2", "resume text");

        assertEquals("deepseek-ai/DeepSeek-V3.2", request.getString("model"));
        assertTrue(request.getJSONArray("messages")
                .getJSONObject(0).getString("content").contains("resume text"));
        assertEquals(false, request.getBooleanValue("stream"));
    }

    @Test
    void visionRequestUsesDataUrlsForResumeImages() {
        JSONObject request = SiliconFlowFileAIService.buildVisionRequest(
                "Qwen/Qwen3-VL-32B-Thinking", List.of("data:image/png;base64,abc"));
        JSONArray content = request.getJSONArray("messages").getJSONObject(0).getJSONArray("content");

        assertEquals("Qwen/Qwen3-VL-32B-Thinking", request.getString("model"));
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals("image_url", content.getJSONObject(1).getString("type"));
        assertEquals("data:image/png;base64,abc",
                content.getJSONObject(1).getJSONObject("image_url").getString("url"));
    }

    @Test
    void responseParserReturnsOnlyTheModelContent() {
        JSONObject response = new JSONObject();
        JSONArray choices = new JSONArray();
        JSONObject choice = new JSONObject();
        choice.put("message", new JSONObject().fluentPut("content", "parsed resume"));
        choices.add(choice);
        response.put("choices", choices);

        assertEquals("parsed resume", SiliconFlowFileAIService.extractContent(response.toJSONString()));
        assertTrue(SiliconFlowFileAIService.extractContent("{}").isBlank());
    }

    @Test
    void normalizesTheConfiguredResumeModelEndpoint() {
        assertEquals("https://api.siliconflow.cn/v1",
                SiliconFlowFileAIService.normalizeEndpoint("https://api.siliconflow.cn/v1/"));
        assertEquals("https://api.siliconflow.cn/v1",
                SiliconFlowFileAIService.normalizeEndpoint("https://api.siliconflow.cn"));
    }
}
