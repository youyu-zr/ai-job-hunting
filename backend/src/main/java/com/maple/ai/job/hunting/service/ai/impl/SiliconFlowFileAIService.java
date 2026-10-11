package com.maple.ai.job.hunting.service.ai.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.maple.ai.job.hunting.common.ai.AIConfigHelper;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.model.AiFileResolveResult;
import com.maple.ai.job.hunting.service.ai.AIService;
import com.maple.ai.job.hunting.utils.FileTypeDetector;
import com.maple.ai.job.hunting.utils.ResumeTextExtractor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * SiliconFlow-compatible resume fallback. Local extraction remains the fast path;
 * this service handles short text, images, and scanned PDFs without a provider file API.
 */
@Service("silicon-flow-file")
@Slf4j
public class SiliconFlowFileAIService implements AIService {

    private static final String RESUME_TEXT_INSTRUCTION = """
            请从下面的简历文本中整理出完整、可检索的简历正文，保留姓名、联系方式、教育经历、工作经历、项目、技能、证书和求职方向等事实。
            只根据输入内容整理，不要补写不存在的经历，不要评价候选人，不要输出解释或 Markdown。
            【待整理简历文本】
            """;

    private static final String RESUME_VISION_INSTRUCTION = """
            请准确识别图片中的简历内容，按从上到下的阅读顺序输出完整中文文本。
            保留姓名、联系方式、教育经历、工作经历、项目、技能、证书和求职方向等事实；看不清的内容不要猜测。
            只输出识别到的简历文本，不要输出解释、JSON 或 Markdown。
            """;

    private RestClient restClient;

    private String endpoint;

    @Value("${siliconflow.api-key:}")
    private String apiKey;

    @Value("${siliconflow.base-url:https://api.siliconflow.cn/v1}")
    private String baseUrl;

    @Value("${siliconflow.resume-text-model:deepseek-ai/DeepSeek-V3.2}")
    private String textModel;

    @Value("${siliconflow.resume-vision-model:Qwen/Qwen3-VL-32B-Thinking}")
    private String visionModel;

    @Value("${siliconflow.resume-timeout-seconds:90}")
    private Integer resumeTimeoutSeconds;

    @Resource
    private AIConfigHelper aiConfigHelper;

    @PostConstruct
    void initializeModelClient() {
        endpoint = normalizeEndpoint(baseUrl);
        aiConfigHelper.validateModelEndpoint(endpoint);
        restClient = aiConfigHelper.buildRestClient(resumeTimeoutSeconds).build();
    }

    @Override
    public AiFileResolveResult readFile(InputStream inputStream, String ask) {
        if (inputStream == null) {
            throw new ApplicationException("简历文件为空");
        }

        try {
            byte[] bytes = inputStream.readAllBytes();
            if (bytes.length == 0) {
                throw new ApplicationException("简历文件为空");
            }

            String type = FileTypeDetector.detectFileType(bytes);
            String extracted = ResumeTextExtractor.extract(bytes);
            String content;
            if ("png".equals(type) || "jpg".equals(type)) {
                content = callModel(visionModel, buildVisionRequest(visionModel,
                        List.of(toDataUrl(type, bytes))));
            } else if ("pdf".equals(type) && !ResumeTextExtractor.hasUsableText(extracted)) {
                content = callModel(visionModel, buildVisionRequest(visionModel, renderPdfPages(bytes)));
            } else {
                if (StringUtils.isBlank(extracted)) {
                    throw new ApplicationException("暂不支持识别该简历文件，请使用 PDF、DOCX、TXT 或图片格式");
                }
                content = callModel(textModel, buildTextRequest(textModel, extracted));
            }

            if (StringUtils.isBlank(content)) {
                throw new ApplicationException("SiliconFlow 未返回简历内容");
            }
            return AiFileResolveResult.builder()
                    .resolveResultType(com.maple.ai.job.hunting.emums.AiFileResolveResultTypeEnum.UNRESOLVED)
                    .originalFileContent(content.trim())
                    .build();
        } catch (ApplicationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("SiliconFlow resume parsing failed: {}", e.getClass().getSimpleName());
            throw new ApplicationException("SiliconFlow 简历解析失败，请检查 API 配置或更换文件后重试");
        }
    }

    @Override
    public com.maple.ai.job.hunting.model.ChatSessionResult sessionChat(String sessionId, String ask) {
        throw new ApplicationException("SiliconFlow 文件解析服务不提供聊天会话");
    }

    @Override
    public String askAndAnswer(String ask) {
        throw new ApplicationException("SiliconFlow 文件解析服务不提供普通问答");
    }

    static JSONObject buildTextRequest(String model, String resumeText) {
        JSONObject request = baseRequest(model);
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", RESUME_TEXT_INSTRUCTION + StringUtils.defaultString(resumeText));
        request.put("messages", new JSONArray().fluentAdd(message));
        return request;
    }

    static JSONObject buildVisionRequest(String model, List<String> imageDataUrls) {
        JSONObject request = baseRequest(model);
        JSONArray content = new JSONArray();
        content.add(new JSONObject().fluentPut("type", "text").fluentPut("text", RESUME_VISION_INSTRUCTION));
        for (String imageDataUrl : imageDataUrls) {
            JSONObject imageUrl = new JSONObject().fluentPut("url", imageDataUrl);
            content.add(new JSONObject().fluentPut("type", "image_url").fluentPut("image_url", imageUrl));
        }
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", content);
        request.put("messages", new JSONArray().fluentAdd(message));
        return request;
    }

    static String extractContent(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return "";
        }
        try {
            JSONObject response = JSON.parseObject(responseBody);
            JSONArray choices = response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return "";
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            return message == null ? "" : StringUtils.defaultString(message.getString("content"));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JSONObject baseRequest(String model) {
        JSONObject request = new JSONObject();
        request.put("model", model);
        request.put("stream", false);
        request.put("temperature", 0.1);
        return request;
    }

    private String callModel(String model, JSONObject request) {
        if (StringUtils.isBlank(apiKey)) {
            throw new ApplicationException("未配置 SiliconFlow API Key");
        }
        String body = restClient.post()
                .uri(endpoint + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json")
                .body(request.toJSONString())
                .retrieve()
                .body(String.class);
        String content = extractContent(body);
        if (StringUtils.isBlank(content)) {
            throw new ApplicationException("SiliconFlow 返回内容为空");
        }
        return content;
    }

    static String normalizeEndpoint(String baseUrl) {
        String endpoint = StringUtils.removeEnd(StringUtils.defaultString(baseUrl).trim(), "/");
        if (!endpoint.endsWith("/v1")) {
            endpoint += "/v1";
        }
        return endpoint;
    }

    private static String toDataUrl(String type, byte[] bytes) {
        String mediaType = "png".equals(type) ? "image/png" : "image/jpeg";
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static List<String> renderPdfPages(byte[] bytes) throws IOException {
        List<String> images = new ArrayList<>();
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = Math.min(document.getNumberOfPages(), 3);
            for (int page = 0; page < pageCount; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 144);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                images.add(toDataUrl("png", output.toByteArray()));
            }
        }
        return images;
    }
}
