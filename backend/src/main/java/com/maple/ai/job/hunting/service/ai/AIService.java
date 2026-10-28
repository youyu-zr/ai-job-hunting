package com.maple.ai.job.hunting.service.ai;

import com.maple.ai.job.hunting.model.AiFileResolveResult;
import com.maple.ai.job.hunting.model.ChatSessionResult;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;

import java.io.InputStream;

/**
 * @author maple
 * Created Date: 2024/4/23 14:14
 * Description:
 */


public interface AIService {

    ChatSessionResult sessionChat(String sessionId, String ask);

    /**
     * 一问一答
     *
     * @param ask 问题
     * @return 回答
     */
    String askAndAnswer(String ask);

    /**
     * Sends policy and untrusted data as separate chat roles.
     * Implementations that cannot preserve roles must fail closed.
     */
    default String askAndAnswer(String systemPrompt, String userPrompt) {
        throw new ApplicationException("当前 AI 服务不支持分离系统规则与用户内容");
    }

    /** 带温度的分离角色调用；不支持温度的实现按默认温度处理。 */
    default String askAndAnswer(String systemPrompt, String userPrompt, float temperature) {
        return askAndAnswer(systemPrompt, userPrompt);
    }

    /** 把会话里最后一条 AI 回复换成实际发出的内容；replacement 为空则删掉。默认不支持会话的实现什么也不做。 */
    default void replaceLastSessionAnswer(String sessionId, String replacement) {
    }

    /** 追加一条 HR 消息和一条实际发出的回复（没调 AI 直接回复时用）。 */
    default void appendSessionExchange(String sessionId, String hrMessage, String reply) {
    }

    /**
     * 读文件
     *
     * @param inputStream 流文件
     * @param ask         问题
     * @return 回答
     */
    AiFileResolveResult readFile(InputStream inputStream, String ask);
}
