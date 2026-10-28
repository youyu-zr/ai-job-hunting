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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        msgSessionDOList.forEach(this::restoreSession);
    }

    /** Restore persisted context when a paused conversation is explicitly resumed. */
    public void restoreSession(MsgSessionDO session) {
        if (session == null || session.getId() == null
                || !Objects.equals(AITypeEnum.OPENAI.getCode(), session.getAiType())
                || session.getMsgContext() == null || session.getMsgContext().isBlank()) {
            return;
        }
        try {
            List<OpenaiMessage> messages = JSONUtil.parseArray(session.getMsgContext())
                    .toList(OpenaiMessage.class);
            if (messages != null) {
                getMsgSessionMap().putIfAbsent(session.getId().toString(),
                        new CopyOnWriteArrayList<>(messages));
            }
        } catch (RuntimeException e) {
            log.warn("会话上下文恢复失败 sessionId:{} errorType:{}",
                    session.getId(), e.getClass().getSimpleName());
        }
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

    @Override
    public void replaceLastSessionAnswer(String sessionId, String replacement) {
        List<Message> messageList = getMsgSessionMap().get(sessionId);
        if (messageList == null || messageList.isEmpty()) {
            return;
        }
        int last = messageList.size() - 1;
        if (messageList.get(last).getMessageType() != MessageType.ASSISTANT) {
            return;
        }
        if (replacement == null || replacement.isBlank()) {
            messageList.remove(last);
        } else {
            messageList.set(last, new OpenaiMessage(MessageType.ASSISTANT, replacement));
        }
        persistentMessage(sessionId, messageList);
    }

    @Override
    public void appendSessionExchange(String sessionId, String hrMessage, String reply) {
        List<Message> messageList = getMsgSessionMap().computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        if (messageList.isEmpty()) {
            messageList.add(new OpenaiMessage(MessageType.SYSTEM, buildSystemFirstPrompt()));
        }
        messageList.add(new OpenaiMessage(MessageType.USER, hrMessage));
        messageList.add(new OpenaiMessage(MessageType.ASSISTANT, reply));
        persistentMessage(sessionId, messageList);
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
    public String askAndAnswer(String systemPrompt, String userPrompt) {
        OpenAiChatClient client = getClient();
        try {
            ChatResponse response = client.call(createRoleSeparatedPrompt(systemPrompt, userPrompt));
            Generation generation = response == null ? null : response.getResult();
            return generation == null || generation.getOutput() == null
                    ? ""
                    : generation.getOutput().getContent();
        } catch (Exception e) {
            if (client instanceof OpenAIPoolConfig.OpenAIPoolClient openAIPoolClient) {
                throw new AIPowerException(openAIPoolClient.getName(), e.getMessage());
            }
            throw new AIPowerException("unknown", e.getMessage());
        }
    }

    static Prompt createRoleSeparatedPrompt(String systemPrompt, String userPrompt) {
        return new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)),
                OpenAiChatOptions.builder().withTemperature(0.0f).build());
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
