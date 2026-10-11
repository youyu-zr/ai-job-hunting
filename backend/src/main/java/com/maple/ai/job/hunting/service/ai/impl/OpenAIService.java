package com.maple.ai.job.hunting.service.ai.impl;

import cn.hutool.json.JSONUtil;
import com.maple.ai.job.hunting.config.ai.OpenAIPoolConfig;
import com.maple.ai.job.hunting.emums.AITypeEnum;
import com.maple.ai.job.hunting.entity.MsgSessionDO;
import com.maple.ai.job.hunting.frame.exp.AIPowerException;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.model.AiFileResolveResult;
import com.maple.ai.job.hunting.model.ChatSessionResult;
import com.maple.ai.job.hunting.model.openai.OpenaiMessage;
import com.maple.ai.job.hunting.service.ai.AbstractAIService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author maple
 * Created Date: 2024/5/8 14:18
 * Description:
 */

@Service("openai")
public class OpenAIService extends AbstractAIService {

    private static final Logger log = LoggerFactory.getLogger(OpenAIService.class);
    @Resource
    private OpenAiChatClient openAiChatClient;

    private static final Map<String, List<Message>> SESSION_MAP = new ConcurrentHashMap<>();

    public Map<String, List<Message>> getMsgSessionMap() {
        return SESSION_MAP;
    }


    @PostConstruct
    public void init() {
        log.info("OpenAIService init");
        List<MsgSessionDO> msgSessionDOList = msgSessionMapper.selectUseListByType(AITypeEnum.OPENAI);
        msgSessionDOList.forEach(msgSessionDO -> getMsgSessionMap().put(msgSessionDO.getId().toString(),
                new CopyOnWriteArrayList<>(JSONUtil.parseArray(msgSessionDO.getMsgContext()).toList(OpenaiMessage.class))));
    }

    @Override
    public ChatSessionResult sessionChat(String sessionId, String ask) {
        List<Message> messageList = getMsgSessionMap().computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        if (CollectionUtils.isEmpty(messageList)) {
            messageList.add(new OpenaiMessage(MessageType.SYSTEM, buildSystemFirstPrompt()));
        } else if (messageList.get(0).getMessageType() == MessageType.SYSTEM) {
            // Apply the current server prompt to existing sessions while
            // preserving their conversation history.
            messageList.set(0, new OpenaiMessage(MessageType.SYSTEM, buildSystemFirstPrompt()));
        } else {
            messageList.add(0, new OpenaiMessage(MessageType.SYSTEM, buildSystemFirstPrompt()));
        }

        messageList.add(new OpenaiMessage(MessageType.USER, ask));
        // The current HR message must be inside this request, not only persisted
        // for a later turn. Use a snapshot so prompt cleanup cannot rewrite history.
        Prompt prompt = new Prompt(buildPromptMessages(messageList));
        OpenAiChatClient client = getClient();

        ChatResponse chatResponse;
        try {
            chatResponse = client.call(prompt);
        } catch (Throwable e) {
            // 当前ai发生异常，移除之前的用户question
            messageList.remove(messageList.size() - 1);
            if (client instanceof OpenAIPoolConfig.OpenAIPoolClient openAIPoolClient) {
                throw new AIPowerException(openAIPoolClient.getName(), e.getMessage());
            }
            if (this.getClass().equals(CustomOpenAIService.class)) {
                throw new AIPowerException("custom", sessionId, e.getMessage());
            }
            throw new AIPowerException("unknown", e.getMessage());
        }
        Generation generation = chatResponse.getResult();
        if (generation == null) {
            throw new ApplicationException("openai返回结果为空");
        }
        String result = generation.getOutput().getContent();
        // 写回到内存会话
        messageList.add(new OpenaiMessage(MessageType.ASSISTANT, result));

        // 后续持久化消息
        persistentMessage(sessionId, messageList);
        return new ChatSessionResult(sessionId, result);
    }

    protected void persistentMessage(String sessionId, List<Message> messageList) {
        // 数据入库，现在每次存储，调用量大之后，定时存储
        msgSessionMapper.updateMsgContextAndAiType(sessionId, JSONUtil.toJsonStr(messageList), AITypeEnum.OPENAI);
    }

    @Override
    public String askAndAnswer(String ask) {
        String answer;
        OpenAiChatClient client = getClient();
        try {
            answer = client.call(ask);
        } catch (Exception e) {
            if (client instanceof OpenAIPoolConfig.OpenAIPoolClient openAIPoolClient) {
                throw new AIPowerException(openAIPoolClient.getName(), e.getMessage());
            }
            throw new AIPowerException("unknown", e.getMessage());
        }
        return answer;
    }

    @Override
    public AiFileResolveResult readFile(InputStream inputStream, String ask) {
        return null;
    }

    protected OpenAiChatClient getClient() {
        return openAiChatClient;
    }


    /**
     * 删除连续消息
     *
     * @param messageList 消息列表
     * @return {@link List }<{@link Message }>
     * @author gaoping
     */
    public List<Message> removeSuccessiveMessages(List<Message> messageList) {
        if (messageList == null) {
            return null;
        }
        List<Message> normalized = buildPromptMessages(messageList);
        messageList.clear();
        messageList.addAll(normalized);
        return messageList;
    }

    static List<Message> buildPromptMessages(List<Message> messageList) {
        List<Message> result = messageList == null ? new ArrayList<>() : new ArrayList<>(messageList);
        if (result.size() < 3) {
            return result;
        }
        // 从前往后遍历，遇到连续类型相同的消息，移除前一条
        int i = 1;
        while (i < result.size()) {
            Message prev = result.get(i - 1);
            Message curr = result.get(i);
            if (curr.getMessageType() == prev.getMessageType()) {
                result.remove(i - 1);
                // 移除后，i不变，继续比较当前位置和前一位
            } else {
                i++;
            }
        }
        return result;
    }
}
