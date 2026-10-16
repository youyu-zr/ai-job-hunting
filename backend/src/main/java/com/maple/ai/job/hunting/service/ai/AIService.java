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

    /**
     * 读文件
     *
     * @param inputStream 流文件
     * @param ask         问题
     * @return 回答
     */
    AiFileResolveResult readFile(InputStream inputStream, String ask);
}
