import axiosOriginal from "axios";
import {Message, MessageRead, TechwolfChatProtocol} from "../webSocket/protobuf";
import {MessageCache, Tools} from "./utils";
import {MessageMergeBatch, MessageMergeQueue} from "./messageMerge";
import {UserStore} from '../stores'
import {rememberAiSeatStatus} from '../stores/remote'
import logging from "../logging";
import logger from "../logging";
import {LogRecorder} from "../logging/record";
import {BossOperationTypeEnum, JobSeekerClonedAnswerTypeEnum} from "../stores/types";
import {AiPower} from "./aiPower";
import {ElMessage} from "../utils/tools";
import {ElNotification} from "element-plus";
import {SendStateTracker, SendTraceContext} from "./sendState";
import {AiFilterDecision, aiFilterAction, compactAiFilterReason} from "./filterGate";
import {shouldCompleteStoppedReply} from "./replyDisposition";
import {ConversationPauseRegistry} from "./pauseRegistry";
import {canDeliverAiSeatTurn, canScanUnreadConversations, parseAiSeatRuntimeStatus} from "./aiSeatDelivery";
import {getAiSeatStatusRevision} from "../stores/seatState";
import {readElementComponentData, readFriendSource, readMessageSenderId} from "./friendSource";
import {shouldDelegateConversationTextToAi} from "./conversationInput";
import {
    isPendingConversationIdentityVerified,
    shouldTreatPendingConversationAsReplyable,
} from "./pendingConversation";
import {
    allowedPlatformBodyTypeOf,
    bodyTypeOf,
    messageIdOf,
    msgTypeOf,
    senderIdOf,
    senderOf,
} from "../webSocket/packetMessage";
import {normalizedMessageBatch} from "../webSocket/messageBody";

let userStore = null as any;
const remotelyPausedJobs = new Set<string>();

export class BossOption {

    static bossUserInfoMap: Map<number, BossUserInfo> = new Map();
    static logRecorder: LogRecorder = new LogRecorder('recorder');
    static messageCache = new MessageCache();
    private static readonly messageMergeQueue = new MessageMergeQueue<{
        msgObj: TechwolfChatProtocol;
        bossUserInfo: BossUserInfo;
    }>(3500);
    private static unreadScanStarted = false;
    private static unreadScanRunning = false;
    private static unreadScanCache: Map<string, number> = new Map();
    // Keep the in-flight guard short so transient failures can be retried, but
    // keep a successful result long enough for the periodic unread scan to see it.
    private static readonly MESSAGE_IN_FLIGHT_TTL = 10 * 60 * 1000;
    private static readonly MESSAGE_COMPLETED_TTL = 24 * 60 * 60 * 1000;
    private static readonly UNREAD_SCAN_COMPLETED_TTL = 24 * 60 * 60 * 1000;

    static aiIconWebpBase64 = 'data:image/webp;base64,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';
    static template = `
<style>
.chat-container {
    max-width: 1000px;
    margin: 20px auto;
    font-family: Arial, sans-serif;
}

.message {
    display: flex;
    margin: 10px 0;
    align-items: start;
}

.message-user {
    justify-content: flex-start;
    margin-left: -70px;
}

.message-assistant {
    justify-content: flex-end;
    margin-right: -46px;
}

.avatar-container {
    display: flex;
    flex-direction: column;
    align-items: center;
    margin: 0 15px;
    width: 65px;
}

.avatar {
    width: 30px;
    height: 30px;
    border-radius: 50%;
    object-fit: cover;
    margin-bottom: 5px;
}

.user-info {
    font-size: 10px;
    font-weight: bold;
    color: #666;
    text-align: center;
    line-height: 1.3;
    max-width: 65px;
    word-break: break-word;
}

.content {
    max-width: 80%;
    padding: 10px 8px;
    border-radius: 12px;
    line-height: 1.5;
    font-size: 10px;
}

.user-content {
    background: #f1f0f0;
    color: #333;
    margin-left: -20px;
}

.assistant-content {
    background: #96d1d1;
    color: #333;
    margin-right: -20px;
}
</style>

<div class="chat-container">
    <!-- 用户提问 -->
    <div class="message message-user">
        <div class="avatar-container">
            <img class="avatar" src="{{user_avatar}}" alt="提问者头像">
            <span class="user-info">{{user_name}}</span>
        </div>
        <div class="content user-content">
            {{user_question}}
        </div>
    </div>

    <!-- 助理回答 -->
    <div class="message message-assistant">
        <div class="content assistant-content">
            {{assistant_answer}}
        </div>
        <div class="avatar-container">
            <img class="avatar" src="{{assistant_avatar}}" alt="回答者头像">
            <span class="user-info">{{assistant_name}}</span>
        </div>
    </div>
</div>

<!-- 变量替换示例：
{{user_avatar}} → "[user avatar]"
{{user_name}} → "张三"
{{user_question}} → "如何快速掌握响应式布局设计？需要具体的学习路线建议"
{{assistant_avatar}} → "[assistant avatar]"
{{assistant_name}} → "AI助手"
{{assistant_answer}} → "建议分三步学习：1. 掌握媒体查询... 2. 学习弹性盒子布局... 3. 实践栅格系统..."
-->
`;

    static {
        this.loadRecentContact().then(_ => {
        });
        this.startUnreadCompensationScanner();
    }

    constructor() {
        if (!userStore) {
            // 初次加载
            userStore = UserStore()
        }
    }

    public preHandlerMsgByBodyType(msgObj: TechwolfChatProtocol, bossUserInfo: BossUserInfo, text: string): boolean {
        const bodyType = bodyTypeOf(msgObj);

        switch (bodyType) {
            // 简历/联系方式/微信请求统一交给后端岗位闸门处理，前端不再直接接受。
            case 7:
                logging.debug("【处理Boss消息】boss索要简历，联系方式，微信", text, msgObj)
                return true;
            // 页面显示的预览简历提示等系统消息，静默过滤。
            case 12:
            case 4:
            case 8:
                logging.debug("【处理Boss消息】系统消息", text, msgObj)
                return false;
            // 面试卡片交给后端读取上下文并人工接管，前端不接受卡片动作。
            case 14:
                logging.info("【处理Boss消息】面试卡片交后端人工确认", text, msgObj)
                return true;
            case 1:
                return shouldDelegateConversationTextToAi(text);
        }

        return !!text;

    }

    public preHandlerMsgByMsgType(msgObj: TechwolfChatProtocol, text: string): boolean {
        const msgType = msgTypeOf(msgObj);

        switch (msgType) {
            case 1:
                // boss发送过来的普通文本消息
                return true;
            case 3:
                // boss的打招呼消息 (不仅是打招呼，还有其他消息,q目前知道的有询问接受地址)
                // 【还有发送简历之后页面显示的预览简历信息；以及发送简历请求后hr拒绝接受简历的普通消息】这些都应该是需要直接在这里过滤的。现在先在后面的bodyType中过滤掉
                // logging.debug("【处理Boss消息-忽略】boss打招呼消息", text)
                // return false;
                return true;
            case 4:
                // boss系统提示消息
                logging.debug("【处理Boss消息-忽略】boss系统提示消息", text)
                return false;
            default:
                return true;
        }
    }

    public buildNoticeHtml(
        fromName: string,
        fromAvatar: string,
        jobTitle: string,
        question: string,
        answer: string,
        decision?: AiFilterDecision | null,
    ) {
        const jobSummary = `岗位：${this.compactNoticeText(jobTitle, 120)}`;
        const hrSummary = `HR消息摘要：${this.compactNoticeText(question, 180)}`;
        const decisionSummary = decision
            ? `AI结论：${aiFilterAction(decision)}；原因：${compactAiFilterReason(decision)}`
            : 'AI结论：已通过岗位闸门；原因：允许继续沟通';
        return BossOption.template
            .replace(/{{user_name}}/g, this.escapeNoticeHtml(fromName))
            .replace(/{{user_avatar}}/g, this.escapeNoticeHtml(fromAvatar))
            .replace(/{{user_question}}/g, this.escapeNoticeHtml(`${jobSummary}；${decisionSummary}；${hrSummary}`))
            .replace(/{{assistant_name}}/g, 'AI助手')
            .replace(/{{assistant_answer}}/g, this.escapeNoticeHtml(this.compactNoticeText(answer, 260)))
            .replace(/{{assistant_avatar}}/g, this.escapeNoticeHtml(BossOption.aiIconWebpBase64));
    }

    public async handlerBossMessage(msgObj: TechwolfChatProtocol, bossId: number, text: string): Promise<boolean> {
        if (!userStore) {
            userStore = UserStore();
        }
        // 坐席关闭时不消费消息去重窗口；开启坐席后，历史未读仍应有机会被补扫。
        if (!userStore.user.aiSeatStatus) {
            logger.info("AI坐席未开启，不处理消息");
            return false;
        }
        const senderId = senderIdOf(msgObj);
        if (senderId && senderId !== String(bossId)) {
            logger.warn("消息发送者与当前联系人不匹配，停止处理");
            BossOption.messageCache.markMessageCompletedWithoutReply(
                bossId,
                text,
                BossOption.MESSAGE_COMPLETED_TTL,
            );
            return true;
        }
        if (BossOption.messageCache.isMessageProcessed(bossId, text)) {
            logging.trace("【跳过重复消息】:", bossId, text);
            return true;
        }
        // 先标记再进入合并窗口，避免 WebSocket 与未读补扫同时触发同一句消息造成重复回复。
        BossOption.messageCache.markMessageAsProcessed(bossId, text, BossOption.MESSAGE_IN_FLIGHT_TTL)

        if (!this.preHandlerMsgByMsgType(msgObj, text)) {
            BossOption.markMessageAsCompleted(bossId, text);
            return true;
        }

        let bossUserInfo: BossUserInfo | undefined;
        try {
            bossUserInfo = await this.getBossUserInfoByBossId(bossId);
        } catch (e) {
            BossOption.messageCache.removeMessage(bossId, text);
            BossOption.logRecorder.error("【处理Boss消息-失败】获取联系人信息异常", e);
            return false;
        }
        if (!bossUserInfo) {
            const bodyType = bodyTypeOf(msgObj);
            // 15 => 过滤活动通知
            if (bodyType === 15) {
                BossOption.markMessageAsCompleted(bossId, text);
                return true;
            }
            BossOption.messageCache.removeMessage(bossId, text);
            BossOption.logRecorder.error("【处理Boss消息-失败】无法获取联系人信息", text)
            return false;
        }

        if (!this.preHandlerMsgByBodyType(msgObj, bossUserInfo, text)) {
            BossOption.markMessageAsCompleted(bossId, text);
            return true;
        }
        if (BossOption.messageCache.hasPendingOperation(bossId, text, 'SEND_RESUME')) {
            logging.warn("【简历发送结果不确定】为避免重复发送，已停止自动重试，请先人工检查该会话 bossId:", bossId);
            BossOption.markMessageAsCompleted(bossId, text);
            return true;
        }
        logger.debug("接收消息解码内容：", text)

        const jobKey = BossOption.buildJobKey(bossUserInfo);
        return BossOption.messageMergeQueue.enqueue(
            jobKey,
            text,
            {msgObj, bossUserInfo},
            (batch) => this.processMergedMessages(batch, jobKey),
        );
    }

    private async processMergedMessages(
        batch: MessageMergeBatch<{ msgObj: TechwolfChatProtocol; bossUserInfo: BossUserInfo }>,
        jobKey: string,
    ): Promise<boolean> {
        const firstItem = batch.items[0];
        const bossUserInfo = firstItem.bossUserInfo;
        const normalizedBatch = normalizedMessageBatch(batch.texts);
        const mergedText = normalizedBatch.text;
        BossOption.logRecorder.info("【合并HR连续消息】bossId:", bossUserInfo.bossId,
            "count:", batch.texts.length, "textLength:", mergedText.length);
        if (normalizedBatch.tooLong) {
            BossOption.logRecorder.warn("消息超过自动处理长度上限，保留为未解决消息",
                "count:", batch.texts.length, "textLength:", mergedText.length);
            this.markBatchMessagesUnresolved(batch);
            ElNotification({
                type: 'warning',
                title: '消息未自动处理',
                message: '消息内容较长，未自动回复；请打开会话查看',
                duration: 0,
                showClose: true,
            });
            return true;
        }

        // 记录本轮的暂停代次：模型生成期间用户手动暂停会递增代次，
        // 发送前核对失败就丢弃这条回复，而不是把它补发出去。
        const initialStatus = await this.loadAiSeatRuntimeStatus(jobKey);
        if (!initialStatus) {
            BossOption.logRecorder.warn("【自动回复暂缓】服务端坐席状态暂时无法确认，保留消息等待重试");
            this.removeBatchMessages(batch);
            return false;
        }
        if (initialStatus.manuallyPaused || initialStatus.gatePaused) {
            this.removeBatchMessages(batch);
            return false;
        }
        const turnGeneration = ConversationPauseRegistry.beginTurn(jobKey);
        const turnSeatRevision = getAiSeatStatusRevision();
        const canDeliverLocally = () => canDeliverAiSeatTurn({
            aiSeatEnabled: Boolean(userStore?.user?.aiSeatStatus),
            turnSeatRevision,
            currentSeatRevision: getAiSeatStatusRevision(),
            jobKey,
            turnGeneration,
            serverStatusFresh: true,
            serverSeatEnabled: initialStatus.globalEnabled,
            serverSessionPaused: initialStatus.manuallyPaused,
            serverGatePaused: initialStatus.gatePaused,
            turnServerRevision: initialStatus.globalRevision,
            currentServerRevision: initialStatus.globalRevision,
            turnSessionRevision: initialStatus.sessionRevision,
            currentSessionRevision: initialStatus.sessionRevision,
        });
        let serverTurnRevoked = false;
        const canDeliverAtServer = async () => {
            if (!canDeliverLocally()) {
                return false;
            }
            const latestStatus = await this.loadAiSeatRuntimeStatus(jobKey);
            if (!latestStatus) {
                return false;
            }
            if (latestStatus.globalEnabled !== Boolean(userStore?.user?.aiSeatStatus)) {
                rememberAiSeatStatus(latestStatus.globalEnabled, userStore?.user);
            }
            if (latestStatus.manuallyPaused || latestStatus.gatePaused) {
                serverTurnRevoked = true;
                if (!ConversationPauseRegistry.isPaused(jobKey)) {
                    ConversationPauseRegistry.pause(jobKey);
                }
                return false;
            }
            if (latestStatus.globalRevision !== initialStatus.globalRevision
                || latestStatus.sessionRevision !== initialStatus.sessionRevision) {
                serverTurnRevoked = true;
                return false;
            }
            return canDeliverLocally();
        };

        if (!canDeliverLocally()) {
            this.removeBatchMessages(batch);
            return false;
        }

        return AiPower.ask(
            mergedText,
            jobKey,
            bossUserInfo,
            AiPower.SEMANTIC_SCREENING_PROMPT,
            allowedPlatformBodyTypeOf(firstItem.msgObj),
        ).then(async resp => {
            const canDeliverTurn = canDeliverLocally;
            const canDeliverImmediatelyBeforeSend = canDeliverAtServer;
            const canDeliverStateChanged = () => serverTurnRevoked
                || !canDeliverLocally();
            const markBlockedTurn = () => {
                if (canDeliverStateChanged()) {
                    this.markBatchMessagesAsCompleted(batch);
                    return true;
                }
                this.removeBatchMessages(batch);
                return false;
            };
            const data = resp?.data?.data || {};
            const answerTypeList: number[] = Array.isArray(data.answerTypeList)
                ? data.answerTypeList.map((value: unknown) => Number(value)).filter(Number.isFinite)
                : [];

            if (answerTypeList.length === 0) {
                BossOption.logRecorder.error("AI回复结果缺少可执行类型，已保留本条消息等待补扫");
                this.removeBatchMessages(batch);
                return false;
            }

            // 停止交互
            if (answerTypeList.includes(JobSeekerClonedAnswerTypeEnum.STOP)) {
                const stopReason = String(data?.stopReason || '').trim().toUpperCase();
                if (stopReason === 'AI_PROCESSING_ERROR' || data?.retryable === true) {
                    ElNotification({
                        type: 'error',
                        title: 'AI暂时无法处理这条消息',
                        message: '本条消息没有自动回复，系统会在后续补扫时重试。',
                        duration: 0,
                        showClose: true,
                    });
                    this.removeBatchMessages(batch);
                    return false;
                }
                if (stopReason === 'PROMOTION_SILENT') {
                    BossOption.logRecorder.info("服务器判定本条为推广消息，已静默；AI坐席继续运行");
                    this.markBatchMessagesAsCompleted(batch);
                    return true;
                }
                if (stopReason === 'HIGH_RISK_JOB') {
                    ElNotification({
                        type: 'warning',
                        title: '该岗位已暂停自动沟通',
                        message: '服务器判定当前岗位存在风险；其他岗位和账号坐席不受影响。',
                        duration: 0,
                        showClose: true,
                    });
                    this.markBatchMessagesAsCompleted(batch);
                    return true;
                }
                if (stopReason === 'USER_CONFIRMATION_REQUIRED') {
                    ElNotification({
                        type: 'warning',
                        title: '这条消息需要你确认',
                        message: '当前会话已暂停自动回复，请查看具体面试或承诺事项。',
                        duration: 0,
                        showClose: true,
                    });
                    this.markBatchMessagesAsCompleted(batch);
                    return true;
                }
                if (data?.jobFilterDecision) {
                    this.showAiConversationDecision(bossUserInfo, data.jobFilterDecision);
                }
                if (shouldCompleteStoppedReply(data)) {
                    this.markBatchMessagesAsCompleted(batch);
                } else {
                    BossOption.logRecorder.warn("STOP 缺少明确停止原因，保留给未读补扫重试");
                    this.removeBatchMessages(batch);
                    return false;
                }
                logging.info("【处理Boss消息-忽略】停止交互")
                return true;
            }
            // ai服务异常
            if (answerTypeList.includes(JobSeekerClonedAnswerTypeEnum.AI_SERVICE_EXCEPTION)) {
                logging.info("AI服务异常，暂时无法处理消息")
                ElMessage({
                    type: 'error',
                    message: 'AI服务异常，暂时无法处理消息（请联系管理员处理）',
                })
                this.removeBatchMessages(batch);
                return false;
            }

            // 文本回复
            if (answerTypeList.includes(JobSeekerClonedAnswerTypeEnum.MSG_TEXT)) {
                const replyAlreadySent = batch.texts.some(text =>
                    BossOption.messageCache.wasReplySent(bossUserInfo.bossId, text));
                if (!replyAlreadySent) {
                    const html = this.buildNoticeHtml(
                        senderOf(firstItem.msgObj).name,
                        senderOf(firstItem.msgObj).avatar,
                        bossUserInfo.jobTitle,
                        mergedText,
                        data.answerContent,
                        AiPower.getCachedFilterDecision(bossUserInfo),
                    )
                    if (!canDeliverTurn()) {
                        // 用户在生成期间暂停或恢复了会话：这条回复已过期，
                        // 或关闭/重新开启了总开关；标记完成以免断线补扫重复发送。
                        BossOption.logRecorder.info("【自动回复已取消】生成期间总开关或会话状态发生变化，bossId:", bossUserInfo.bossId);
                        this.markBatchMessagesAsCompleted(batch);
                        return true;
                    }
                    const sent = await this.sendMsg(
                        bossUserInfo.bossId,
                        data.answerContent + Tools.getEndChar(),
                        undefined,
                        html,
                        canDeliverImmediatelyBeforeSend,
                    )
                    if (!sent) {
                        return markBlockedTurn();
                    }
                    this.markBatchRepliesAsSent(batch);
                }
            }
            // boss操作
            if (answerTypeList.includes(JobSeekerClonedAnswerTypeEnum.BOSS_OPERATION)) {
                const operationTypeList = data?.operationTypeList || [];
                if (operationTypeList.includes(BossOperationTypeEnum.SEND_RESUME)) {
                    if (!canDeliverTurn()) {
                        this.markBatchMessagesAsCompleted(batch);
                        return true;
                    }
                    const sent = await this.sendResumeFile(bossUserInfo.bossId, canDeliverImmediatelyBeforeSend);
                    if (!sent) {
                        if (canDeliverStateChanged()) {
                            this.markBatchMessagesAsCompleted(batch);
                            return true;
                        }
                        BossOption.logRecorder.warn("【简历发送结果不确定】为避免重复发送，请人工检查 BOSS 会话后再决定是否发送 bossId:", bossUserInfo.bossId);
                        this.markBatchMessagesAsCompleted(batch);
                        return true;
                    }
                    this.markBatchMessagesAsCompleted(batch);
                }
            }

            // WS 已读消息；未读补偿扫描构造的历史消息没有真实 mid，不发送伪已读包。
            for (const item of batch.items) {
                const messageId = messageIdOf(item.msgObj);
                if (messageId) {
                    new MessageRead({
                        userId: bossUserInfo.bossId as any,
                        messageId: messageId as any,
                    }).send()
                }
            }

            // 页面已读当前消息 【todo 新消息没有小红点提示了】
            setTimeout(() => {
                const bossEleList = Array.from(document.querySelectorAll('.friend-content, [class*="friend-content"]')) as any[];
                bossEleList.filter((bossEle: any) => bossUserInfo.bossId === BossOption.getBossIdFromSource(BossOption.getFriendSource(bossEle)))
                    .forEach((bossEle: any) => {
                        bossEle.querySelectorAll('.notice-badge').forEach((badgeEle: any) => {
                            badgeEle.remove();
                            return;
                        })
                    })
            }, 500)
            this.markBatchMessagesAsCompleted(batch);
            return true;
        }).catch((e: any) => {
            this.removeBatchMessages(batch, true);
            BossOption.logRecorder.error("【处理Boss消息-失败】AI回复链路异常，可由未读补扫重试", e?.message || e);
            return false;
        })
    }

    private showAiConversationDecision(bossUserInfo: BossUserInfo, result: AiFilterDecision): void {
        const action = aiFilterAction(result);
        const html = [
            `<div><b>岗位：</b>${this.escapeNoticeHtml(bossUserInfo?.jobTitle)}</div>`,
            `<div><b>AI结论：</b>${this.escapeNoticeHtml(action)}</div>`,
            `<div><b>原因：</b>${this.escapeNoticeHtml(compactAiFilterReason(result))}</div>`,
        ].join('');
        ElNotification({
            type: action === 'FILTER' ? 'warning' : 'info',
            title: 'AI岗位判断',
            message: html,
            dangerouslyUseHTMLString: true,
            duration: 6000,
            showClose: true,
        });
    }

    private compactNoticeText(value: any, maxLength: number): string {
        let text = String(value || '').replace(/[\u0000-\u001f\u007f]+/g, ' ').replace(/\s+/g, ' ').trim();
        const detailMarker = text.search(/职位详情|职位描述|岗位职责|任职要求|查看职位/);
        if (detailMarker > 0) {
            text = text.slice(0, detailMarker).trim();
        }
        return text.length > maxLength ? `${text.slice(0, maxLength)}...` : text;
    }

    private escapeNoticeHtml(value: any): string {
        return String(value || '').replace(/[&<>"']/g, (char) => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;',
        } as Record<string, string>)[char]);
    }

    private removeBatchMessages(
        batch: MessageMergeBatch<{ msgObj: TechwolfChatProtocol; bossUserInfo: BossUserInfo }>,
        preserveSentReply = false,
    ): void {
        const bossId = batch.items[0]?.bossUserInfo?.bossId;
        if (!bossId) {
            return;
        }
        for (const text of batch.texts) {
            if (preserveSentReply && (BossOption.messageCache.isMessageCompleted(bossId, text)
                || BossOption.messageCache.wasReplySent(bossId, text)
                || BossOption.messageCache.hasPendingOperation(bossId, text, 'SEND_RESUME'))) {
                continue;
            }
            BossOption.messageCache.removeMessage(bossId, text);
        }
    }

    private static markMessageAsCompleted(bossId: number, text: string): void {
        BossOption.messageCache.markMessageAsCompleted(
            bossId,
            text,
            BossOption.MESSAGE_COMPLETED_TTL,
        );
    }

    private markBatchRepliesAsSent(
        batch: MessageMergeBatch<{ msgObj: TechwolfChatProtocol; bossUserInfo: BossUserInfo }>,
    ): void {
        const bossId = batch.items[0]?.bossUserInfo?.bossId;
        if (!bossId) {
            return;
        }
        for (const text of batch.texts) {
            BossOption.messageCache.markReplySent(
                bossId,
                text,
                BossOption.MESSAGE_COMPLETED_TTL,
            );
        }
    }

    private markBatchPendingOperation(
        batch: MessageMergeBatch<{ msgObj: TechwolfChatProtocol; bossUserInfo: BossUserInfo }>,
        operation: string,
    ): void {
        const bossId = batch.items[0]?.bossUserInfo?.bossId;
        if (!bossId) {
            return;
        }
        for (const text of batch.texts) {
            BossOption.messageCache.markPendingOperation(
                bossId,
                text,
                operation,
                BossOption.MESSAGE_COMPLETED_TTL,
            );
        }
    }

    private markBatchMessagesAsCompleted(
        batch: MessageMergeBatch<{ msgObj: TechwolfChatProtocol; bossUserInfo: BossUserInfo }>,
    ): void {
        const bossId = batch.items[0]?.bossUserInfo?.bossId;
        if (!bossId) {
            return;
        }
        for (const text of batch.texts) {
            BossOption.markMessageAsCompleted(bossId, text);
        }
    }

    private markBatchMessagesUnresolved(
        batch: MessageMergeBatch<{ msgObj: TechwolfChatProtocol; bossUserInfo: BossUserInfo }>,
    ): void {
        const bossId = batch.items[0]?.bossUserInfo?.bossId;
        if (!bossId) {
            return;
        }
        for (const text of batch.texts) {
            BossOption.messageCache.markMessageCompletedWithoutReply(
                bossId,
                text,
                BossOption.MESSAGE_COMPLETED_TTL,
            );
        }
    }


    private static startUnreadCompensationScanner(): void {
        if (BossOption.unreadScanStarted) {
            return;
        }
        BossOption.unreadScanStarted = true;
        const scan = () => {
            BossOption.scanUnreadConversationOnce().catch((e: any) => {
                BossOption.logRecorder.warn("【未读补扫】执行失败", e?.message || e)
            })
        };
        setTimeout(scan, 5000);
        window.setInterval(scan, 15000);
    }

    private static async scanUnreadConversationOnce(): Promise<void> {
        if (BossOption.unreadScanRunning) {
            return;
        }
        if (!userStore) {
            userStore = UserStore();
        }
        BossOption.unreadScanRunning = true;
        try {
            const authoritativeSeatEnabled = await AiPower.getGlobalAiSeatStatus();
            if (!canScanUnreadConversations(authoritativeSeatEnabled)) {
                if (authoritativeSeatEnabled === false && userStore.user?.aiSeatStatus) {
                    rememberAiSeatStatus(false, userStore.user);
                }
                BossOption.logRecorder.info("【未读补扫】账号级AI坐席已关闭或状态未知，未扫描联系人");
                return;
            }
            if (!userStore.user?.aiSeatStatus) {
                rememberAiSeatStatus(true, userStore.user);
            }
            const friendEleList = Array.from(document.querySelectorAll('.friend-content, [class*="friend-content"]')) as any[];
            if (friendEleList.length === 0) {
                return;
            }
            const bossOption = new BossOption();
            // Inspect every rendered contact so an unread contact below the
            // first viewport cannot starve forever. Processing remains one
            // contact per 15-second round to preserve the low-resource limit.
            const prioritizedFriendEleList = [...friendEleList].sort((left, right) => {
                const leftSource = BossOption.getFriendSource(left);
                const rightSource = BossOption.getFriendSource(right);
                return Number(BossOption.hasUnreadMarker(right, rightSource))
                    - Number(BossOption.hasUnreadMarker(left, leftSource));
            });
            let inspected = 0;
            let pendingCount = 0;
            let skippedNoId = 0;
            let skippedNoText = 0;
            let skippedRecent = 0;
            let handledOne = false;
            for (const friendEle of prioritizedFriendEleList) {
                inspected += 1;
                const source = BossOption.getFriendSource(friendEle);
                const bossId = BossOption.getBossIdFromSource(source);
                if (!bossId) {
                    skippedNoId += 1;
                    BossOption.logRecorder.warn("【未读补扫】跳过：联系人ID为空");
                    continue;
                }
                const pending = await BossOption.resolvePendingConversation(friendEle, source);
                if (!pending.needsReply) {
                    continue;
                }
                let text = pending.text;
                if (!text) {
                    skippedNoText += 1;
                    BossOption.logRecorder.warn("【未读补扫】跳过：未提取到待回复文本 bossId:", bossId);
                    continue;
                }
                if (BossOption.wasUnreadRecentlyProcessed(bossId, text)) {
                    skippedRecent += 1;
                    continue;
                }
                pendingCount += 1;
                BossOption.logRecorder.info(
                    pending.source === 'read-pending' ? "【已读待回复补扫】触发AI坐席" : "【未读补扫】触发AI坐席",
                    "bossId:", bossId, "text:", text.slice(0, 60));
                const bossUserInfo = await bossOption.getBossUserInfoByBossId(bossId);
                if (!bossUserInfo) {
                    BossOption.logRecorder.warn("【未读补扫】无法确认目标岗位，跳过以保留原未读状态", "bossId:", bossId);
                    continue;
                }
                const runtimeStatus = await bossOption.loadAiSeatRuntimeStatus(
                    BossOption.buildJobKey(bossUserInfo),
                );
                if (!runtimeStatus
                    || !runtimeStatus.globalEnabled
                    || runtimeStatus.manuallyPaused
                    || runtimeStatus.gatePaused) {
                    BossOption.logRecorder.info("【未读补扫】服务端坐席状态不允许接续，跳过本轮", "bossId:", bossId);
                    continue;
                }
                const msgObj = BossOption.buildSyntheticTextMessage(bossId, source, text);
                const handled = await bossOption.handlerBossMessage(msgObj, bossId, text);
                if (handled) {
                    BossOption.markUnreadScanCompleted(bossId, text);
                    handledOne = true;
                    break;
                }
                BossOption.logRecorder.warn("【未读补扫】本轮未完成，保留下一轮重试 bossId:", bossId);
            }
            BossOption.logRecorder.info(
                "【未读补扫】本轮统计",
                "inspected:", inspected,
                "pending:", pendingCount,
                "noId:", skippedNoId,
                "noText:", skippedNoText,
                "recent:", skippedRecent,
                "handled:", handledOne,
            );
        } finally {
            BossOption.unreadScanRunning = false;
        }
    }

    private static getFriendSource(friendEle: any): any {
        return readFriendSource(friendEle);
    }

    private static hasUnreadMarker(friendEle: any, source: any): boolean {
        const sourceUnread = source?.unread ?? source?.hasUnread ?? source?.isUnread ?? source?.unreadCount;
        if (sourceUnread === true || Number(sourceUnread) > 0) {
            return true;
        }
        return Boolean(friendEle.querySelector(
            '.notice-badge, .friend-content-badge, .badge, [class*="unread"], [class*="notice-badge"], [class*="badge"]'
        ));
    }

    private static getBossIdFromSource(source: any): number {
        const raw = source?.uid || source?.friendId || source?.bossId || source?.userId;
        const bossId = Number(raw);
        return Number.isFinite(bossId) && bossId > 0 ? bossId : 0;
    }

    private static unreadScanKey(bossId: number, text: string): string {
        return `${bossId}:${text.slice(0, 80)}`;
    }

    private static wasUnreadRecentlyProcessed(bossId: number, text: string): boolean {
        const key = BossOption.unreadScanKey(bossId, text);
        const last = BossOption.unreadScanCache.get(key) || 0;
        return Date.now() - last < BossOption.UNREAD_SCAN_COMPLETED_TTL;
    }

    private static markUnreadScanCompleted(bossId: number, text: string): void {
        const key = BossOption.unreadScanKey(bossId, text);
        const now = Date.now();
        BossOption.unreadScanCache.set(key, now);
        if (BossOption.unreadScanCache.size > 200) {
            for (const [cacheKey, timestamp] of BossOption.unreadScanCache.entries()) {
                if (now - timestamp > 2 * BossOption.UNREAD_SCAN_COMPLETED_TTL) {
                    BossOption.unreadScanCache.delete(cacheKey);
                }
            }
        }
    }

    private static extractUnreadPreviewText(friendEle: any, source: any): string {
        const normalize = (value: any) => String(value || '').replace(/\s+/g, ' ').trim();
        const sourceValues = [
            source?.lastMsgText,
            source?.lastMessageText,
            source?.lastMessageContent,
            source?.lastMsgContent,
            source?.showMsg,
            source?.preview,
            source?.messagePreview,
            source?.lastMsg?.text,
            source?.lastMsg?.content,
            source?.lastMessage?.text,
            source?.lastMessage?.content,
            source?.message?.text,
            source?.message?.content,
        ];
        for (const value of sourceValues) {
            const text = normalize(value);
            if (BossOption.isUsableUnreadPreview(text, source)) {
                return text;
            }
        }

        const rawText = String(friendEle?.innerText || friendEle?.textContent || '');
        const lines = rawText.split(/[\r\n]+/).map(normalize).filter(Boolean);
        for (let i = lines.length - 1; i >= 0; i--) {
            if (BossOption.isUsableUnreadPreview(lines[i], source)) {
                return lines[i];
            }
        }
        return '';
    }

    private static isUsableUnreadPreview(text: string, source: any): boolean {
        if (!text || text.length < 2 || text.length > 180) {
            return false;
        }
        if (/^\d+$/.test(text) || /^\d{1,2}:\d{2}$/.test(text) || /^\d{1,2}月\d{1,2}日$/.test(text)) {
            return false;
        }
        if (/^(在线|已读|送达|新消息|沟通过)$/.test(text)) {
            return false;
        }
        const names = [source?.name, source?.brandName, source?.companyName, source?.title]
            .map((item: any) => String(item || '').trim())
            .filter(Boolean);
        if (names.includes(text)) {
            return false;
        }
        return true;
    }

    private static getActiveConversationBossId(): number {
        const activeSelector = [
            '.friend-content.selected',
            '.friend-content.active',
            '[class*="friend-content"].selected',
            '[class*="friend-content"].active',
        ].join(', ');
        const activeFriends = Array.from(document.querySelectorAll(activeSelector)) as any[];
        const activeIds = new Set(activeFriends
            .map(friendEle => BossOption.getBossIdFromSource(BossOption.getFriendSource(friendEle)))
            .filter(bossId => bossId > 0));
        return activeIds.size === 1 ? Array.from(activeIds)[0] : 0;
    }

    private static buildSyntheticTextMessage(bossId: number, source: any, text: string): TechwolfChatProtocol {
        return {
            type: 1,
            messages: [{
                from: {
                    uid: bossId,
                    name: source?.name || source?.bossName || 'HR',
                    avatar: source?.avatar || source?.bossAvatar || '',
                    source: 0,
                },
                to: {
                    uid: Tools.window._PAGE?.uid || '',
                    name: '',
                    source: 0,
                },
                type: 1,
                body: {
                    type: 1,
                    templateId: 1,
                    text,
                },
            } as any],
        } as TechwolfChatProtocol;
    }

    public async sendMsg(bossId: number, msg: string, image: {
        originImage: string,
        tinyImage: string
    } | undefined, html?: string, canSend?: () => boolean | Promise<boolean>): Promise<boolean> {
        return this.getBossUserInfoByBossId(bossId).then(async (bossUserInfo: BossUserInfo | undefined) => {
            if (!bossUserInfo) {
                BossOption.logRecorder.error("发送消息失败，联系人信息获取失败");
                return false;
            }

            // 构建消息并发送
            const trace: SendTraceContext = {
                action: 'AI_REPLY',
                channel: 'ai-seat',
                bossId,
                jobKey: BossOption.getJobKeyByCache(bossId),
                jobTitle: bossUserInfo.jobTitle,
                content: msg,
            };
            SendStateTracker.record(trace, 'GENERATED', 'ai reply generated');
            let message = new Message({
                form_uid: Tools.window._PAGE.uid.toString(),
                to_uid: bossId.toString(),
                to_name: bossUserInfo.encryptBossId,
                content: msg,
                image: undefined,
                trace,
                recipient: {
                    uid: bossUserInfo.bossId,
                    encryptUid: bossUserInfo.encryptBossId,
                    friendSource: bossUserInfo.friendSource || 0,
                    securityId: bossUserInfo.securityId,
                    encryptJobId: bossUserInfo.encryptJobId,
                    jobTitle: bossUserInfo.jobTitle,
                },
            });
            // A generated answer is not delivered until BOSS echoes the
            // candidate's own message back through the chat channel. Waiting
            // here keeps retries and the unread compensator aligned with the
            // actual platform state instead of the local sender invocation.
            if (canSend && !(await canSend())) {
                BossOption.logRecorder.info("自动回复发送前状态已变化，已取消 bossId:", bossId);
                return false;
            }
            const sent = await message.sendAndConfirm()

            if (sent && html) {
                ElNotification({
                    type: 'success',
                    title: 'AI坐席回复',
                    showClose: false,
                    duration: 5000,
                    dangerouslyUseHTMLString: true,
                    message: html,
                })
            }
            return sent;
        }).catch((e: any) => {
            BossOption.logRecorder.error("发送消息失败，联系人信息加载异常", e?.message || e);
            return false;
        })
    }

    public async sendResumeFile(bossId: number, canSend?: () => boolean | Promise<boolean>): Promise<boolean> {
        let resumeId = userStore.user.resumeId;
        if (!resumeId) {
            BossOption.logRecorder.error("简历发送失败：当前账号没有可用简历");
            return false;
        }

        try {
            const bossUserInfo = await this.getBossUserInfoByBossId(bossId);
            if (!bossUserInfo) {
                BossOption.logRecorder.error("简历发送失败：联系人信息获取失败");
                return false;
            }

            BossOption.logRecorder.info("已识别HR明确索要简历，正在调用平台简历交换接口");

            if (canSend && !(await canSend())) {
                BossOption.logRecorder.info("简历发送前坐席状态已变化，已取消 bossId:", bossId);
                return false;
            }
            const response = await axiosOriginal.post("https://www.zhipin.com/wapi/zpchat/exchange/request", {
                securityId: bossUserInfo.securityId,
                type: 3,
                friendSource: bossUserInfo.friendSource,
                encryptResumeId: resumeId
            }, {
                headers: {
                    "Zp_token": Tools.getCookieValue("bst"),
                    'content-type': 'application/x-www-form-urlencoded;charset=UTF-8'
                }
            });
            const code = response?.data?.code;
            if (code !== 0 && code !== 200) {
                BossOption.logRecorder.error("简历发送失败：平台交换接口返回异常", response?.data?.message || response?.data || response);
                return false;
            }
            BossOption.logRecorder.info("简历发送成功：平台已接受交换请求");
            return true;
        } catch (e: any) {
            BossOption.logRecorder.error("简历发送失败：平台交换请求异常", e?.message || e);
            return false;
        }
    }

    private static async resolvePendingConversation(
        friendEle: any,
        source: any,
    ): Promise<{ needsReply: boolean; text: string; source: 'unread' | 'read-pending' | 'unknown' }> {
        const unread = BossOption.hasUnreadMarker(friendEle, source);
        const preview = BossOption.extractUnreadPreviewText(friendEle, source);
        const senderId = BossOption.getLastMessageSenderId(source, friendEle);
        const currentUserId = BossOption.getCurrentUserId();
        const selected = BossOption.isSelectedFriendElement(friendEle);

        if (selected) {
            const latest = BossOption.readLatestConversationMessage();
            const targetBossId = BossOption.getBossIdFromSource(source);
            const activeBossId = BossOption.getActiveConversationBossId();
            if (latest?.text
                && isPendingConversationIdentityVerified(targetBossId, activeBossId, latest.senderId)) {
                const needsReply = shouldTreatPendingConversationAsReplyable({
                    unread,
                    preview: latest.text,
                    lastMessageSenderId: senderId,
                    currentUserId,
                    selected: true,
                    latestSender: latest.sender,
                    latestMessageObserved: true,
                });
                return needsReply
                    ? {needsReply: true, text: latest.text, source: unread ? 'unread' : 'read-pending'}
                    : {needsReply: false, text: '', source: 'unknown'};
            }
            return {needsReply: false, text: '', source: 'unknown'};
        }

        if (shouldTreatPendingConversationAsReplyable({
            unread,
            preview,
            lastMessageSenderId: senderId,
            currentUserId,
            selected: false,
            latestSender: 'unknown',
            expectedSenderId: BossOption.getBossIdFromSource(source),
        })) {
            return {
                needsReply: true,
                text: preview,
                source: unread ? 'unread' : 'read-pending',
            };
        }

        return {needsReply: false, text: '', source: 'unknown'};
    }

    private async loadAiSeatRuntimeStatus(jobKey: string) {
        try {
            const response = await AiPower.getSessionStatus(jobKey);
            const status = parseAiSeatRuntimeStatus(response?.data?.data);
            if (!status) {
                return null;
            }
            if (status.globalEnabled !== Boolean(userStore?.user?.aiSeatStatus)) {
                rememberAiSeatStatus(status.globalEnabled, userStore?.user);
            }
            if (status.manuallyPaused || status.gatePaused) {
                remotelyPausedJobs.add(jobKey);
                if (!ConversationPauseRegistry.isPaused(jobKey)) {
                    ConversationPauseRegistry.pause(jobKey);
                }
            } else if (remotelyPausedJobs.has(jobKey)) {
                remotelyPausedJobs.delete(jobKey);
                ConversationPauseRegistry.resume(jobKey);
            }
            return status;
        } catch (error: any) {
            BossOption.logRecorder.warn("【坐席状态读取失败】本轮不自动发送，等待服务恢复", error?.message || error);
            return null;
        }
    }

    private static isSelectedFriendElement(friendEle: any): boolean {
        if (!friendEle) {
            return false;
        }
        if (friendEle.classList?.contains('selected') || friendEle.classList?.contains('active')) {
            return true;
        }
        return Boolean(friendEle.closest?.('.selected, .active'));
    }

    private static getCurrentUserId(): number {
        const value = Number(Tools.window._PAGE?.uid || 0);
        return Number.isFinite(value) && value > 0 ? value : 0;
    }

    private static getLastMessageSenderId(source: any, friendEle?: any): number | null {
        const directValues = [
            source?.lastMsgFromUid,
            source?.lastMessageFromUid,
            source?.lastMsgSenderUid,
            source?.lastMessageSenderUid,
            source?.lastMsg?.senderId,
            source?.lastMessage?.senderId,
            source?.lastMsg?.from?.uid,
            source?.lastMessage?.from?.uid,
            source?.lastMsg?.sender?.uid,
            source?.lastMessage?.sender?.uid,
        ];
        for (const value of directValues) {
            const parsed = Number(value);
            if (Number.isFinite(parsed) && parsed > 0) {
                return parsed;
            }
        }

        const elementSenderId = Number(friendEle?.getAttribute?.('data-last-message-from-uid')
            || friendEle?.getAttribute?.('data-last-sender-id'));
        if (Number.isFinite(elementSenderId) && elementSenderId > 0) {
            return elementSenderId;
        }

        const explicitMine = [
            source?.lastMsgFromMe,
            source?.lastMessageFromMe,
            source?.isLastMsgMine,
            source?.lastMessageIsMine,
            source?.lastMsg?.isMine,
            source?.lastMessage?.isMine,
        ].find((value: any) => typeof value === 'boolean');
        if (typeof explicitMine === 'boolean') {
            const currentUserId = BossOption.getCurrentUserId();
                return explicitMine && currentUserId > 0 ? currentUserId : (explicitMine ? null : BossOption.getBossIdFromSource(source));
        }
        const elementMine = [
            friendEle?.getAttribute?.('data-last-message-from-me'),
            friendEle?.getAttribute?.('data-last-message-is-mine'),
        ].find((value: any) => value !== null && value !== undefined && value !== '');
        if (elementMine !== undefined) {
            const isMine = elementMine === true || String(elementMine).toLowerCase() === 'true' || String(elementMine) === '1';
            const currentUserId = BossOption.getCurrentUserId();
            return isMine && currentUserId > 0 ? currentUserId : (isMine ? null : BossOption.getBossIdFromSource(source));
        }
        return null;
    }

    private static readLatestConversationMessage(): {
        sender: 'boss' | 'candidate' | 'unknown';
        senderId: number | null;
        text: string;
    } | null {
        const container = document.querySelector(
            '.chat-conversation, .conversation-content, .chat-message-list, [class*="conversation"], [class*="chat-list"]'
        ) as HTMLElement | null;
        if (!container) {
            return null;
        }
        const nodes = Array.from(container.querySelectorAll(
            '.message-item, .chat-message, .conversation-message, [class*="message-item"], [class*="chat-item"]'
        )) as HTMLElement[];
        for (const node of nodes.reverse()) {
            const text = BossOption.readConversationNodeText(node);
            if (!text) {
                continue;
            }
            const sender = BossOption.readConversationNodeSender(node);
            const senderId = BossOption.readConversationNodeSenderId(node);
            return {sender, senderId, text};
        }
        return null;
    }

    private static readConversationNodeSender(node: any): 'boss' | 'candidate' | 'unknown' {
        const props = readElementComponentData(node);
        const currentUserId = BossOption.getCurrentUserId();
        const senderId = BossOption.readConversationNodeSenderId(node);
        if (senderId !== null && currentUserId > 0) {
            return senderId === currentUserId ? 'candidate' : 'boss';
        }
        for (const key of ['isMine', 'isMe', 'isSelf', 'fromMe', 'sentByMe']) {
            if (typeof props?.[key] === 'boolean') {
                return props[key] ? 'candidate' : 'boss';
            }
        }
        const className = String(node?.className || '').toLowerCase();
        if (/(^|[-_ ])(self|mine|me|right|outgoing|sent)([-_ ]|$)/.test(className)) {
            return 'candidate';
        }
        if (/(^|[-_ ])(other|left|incoming|received|boss)([-_ ]|$)/.test(className)) {
            return 'boss';
        }
        return 'unknown';
    }

    private static readConversationNodeSenderId(node: any): number | null {
        return readMessageSenderId(node);
    }

    private static readConversationNodeText(node: HTMLElement): string {
        const text = String(node?.innerText || node?.textContent || '').replace(/\s+/g, ' ').trim();
        if (!text || text.length > 500 || /^(在线|已读|送达|新消息)$/.test(text)) {
            return '';
        }
        return text;
    }

    public static getJobKeyByCache(bossId: number): string {
        let bossUserInfo = BossOption.getBossUserInfoByCache(bossId);
        if (!bossUserInfo) {
            return '';
        }
        return this.buildJobKey(bossUserInfo);
    }

    public static getBossUserInfoByCache(bossId: number): BossUserInfo | undefined {
        return BossOption.bossUserInfoMap.get(bossId);
    }

    public static buildJobKey(bossUserInfo: BossUserInfo): string {
        if (!bossUserInfo) {
            return '';
        }

        return bossUserInfo.encryptJobId + ":" + Tools.window._PAGE.uid;
    }


    public static async loadRecentContact(): Promise<void> {
        // 加载最近30天内联系人
        this.obtainRecentContactBossId()
            .then(bossIdList => this.obtainBossUserInfo(bossIdList))
            .then(bossUserInfoList => {
                bossUserInfoList.forEach((bossUserInfo: BossUserInfo) => {
                    this.bossUserInfoMap.set(bossUserInfo.bossId, bossUserInfo);
                })
            }).catch(e => {
            logging.error("加载最近联系人失败", e)
        })
    }

    public async getBossUserInfoByBossId(bossId: number): Promise<BossUserInfo | undefined> {
        // 先从缓存中获取
        let bossUserInfo = BossOption.bossUserInfoMap.get(bossId);
        if (bossUserInfo) {
            return bossUserInfo;
        }

        // 调用接口获取
        let bossUserInfoList = await BossOption.obtainBossUserInfo([bossId]);
        if (bossUserInfoList.length === 0) {
            return undefined;
        }
        // 添加到缓存
        BossOption.bossUserInfoMap.set(bossId, bossUserInfoList[0]);
        return bossUserInfoList[0];
    }

    public static async obtainRecentContactBossId(): Promise<number[]> {
        let resp = await axiosOriginal.get("https://www.zhipin.com/wapi/zprelation/friend/geekFilterByLabel?labelId=0")
        if (resp.data.message === '当前登录状态已失效') {
            throw new Error("未登录Boss");
        }
        let friendList: any = resp.data.zpData?.friendList;
        if (!friendList || !friendList?.length || friendList?.length === 0) {
            return [];
        }
        return friendList.map((friend: any) => friend.friendId);
    }

    public static async obtainBossUserInfo(bossIdList: number[]): Promise<BossUserInfo[]> {
        if (bossIdList && bossIdList.length && bossIdList.length >= 200) {
            // 超过199接口会报错
            bossIdList = bossIdList.slice(0, 199);
        }
        let bossIdListStr = bossIdList.map((bossId: any) => bossId.toString()).join(',');
        let resp: any = await axiosOriginal.get("https://www.zhipin.com/wapi/zprelation/friend/getGeekFriendList.json?friendIds=" + bossIdListStr)
        let friendList = resp.data.zpData?.result
        if (!friendList || friendList.length === 0) {
            return [];
        }
        return friendList.map((friend: any) => {
            return {
                bossId: friend.uid,
                encryptBossId: friend.encryptBossId,
                securityId: friend.securityId,
                encryptJobId: friend.encryptJobId,
                lid: friend.lid,
                friendSource: friend.friendSource,
                jobTitle: friend.brandName + "-" + friend.title + "-" + friend.name,
            } as BossUserInfo;
        });
    }

}
