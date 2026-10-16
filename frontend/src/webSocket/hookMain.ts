import {decodeMqttAndProtobuf, getMsgBody, normalizeNumber} from './utils';
import logger from "../logging";
import {BossOption} from '../platform/bossPlatform';
import {Tools} from "../platform/utils";
import {TechwolfChatProtocol, protoDefinition} from "./protobuf";
import {AiPower} from "../platform/aiPower";
import {LogRecorder} from "../logging/record";
import {UserStore} from "../stores";
import {SendStateTracker} from "../platform/sendState";
import {isUserConfigReady, waitForUserConfigReady} from "../stores/remote";
import {installReceiveDispatchHook, installReceiveListener} from "./receiveHook";
import {splitChatProtocolMessages} from './messageBatch';
import {bodyImageOf, firstMessageOf} from './packetMessage';

const hookWindow = Tools.window as typeof Tools.window & {
    __AI_JOB_HUNTING_WS_HOOK__?: boolean;
};
const ownsWebSocketHook = !hookWindow.__AI_JOB_HUNTING_WS_HOOK__;
if (ownsWebSocketHook) {
    hookWindow.__AI_JOB_HUNTING_WS_HOOK__ = true;
}

const originalWebSocket = Tools.window.WebSocket as typeof WebSocket;
const nativeWebSocketSend = originalWebSocket.prototype.send;
const TARGET_URL: string = 'chat';
const logRecorder: LogRecorder = new LogRecorder('hook');
logRecorder.info("---------------------------------------------------------------");
logRecorder.info("WS Hook Start");

//======================================================================================================================
// 预先定义的ws send以及onmessage拦截
let sendInterceptor: ((data: any) => any) | null = null;
let receiveInterceptor: ((data: any) => any) | null = null;

//======================================================================================================================
let hookMap = new Map()
let hookPrototype = false
let receiveDispatchHookInstalled = false;

class WebSocketProxy extends originalWebSocket {
    constructor(url: string, protocols?: string | string[]) {
        super(url, protocols);
        url = url.replace(":443", "");
        const shouldHook = url.includes(TARGET_URL);

        if (!shouldHook) {
            return this;
        }
        hookPrototype = true;
        hookMap.set(`${url}:${hookMap.size}`, this)
        logRecorder.info("WS Hook success ", url)
        hookReceiveForInstance(this);

        this.send = (data: any) => {
            if (sendInterceptor) {
                data = sendInterceptor(data);
            }
            return nativeWebSocketSend.call(this, data);
        };
    }
}

if (ownsWebSocketHook) {
    Tools.window.WebSocket = WebSocketProxy as any;
}

// 更加健壮的初始化逻辑
if (ownsWebSocketHook && !Tools.window.ChatWebsocket) {
    setChatWebsocket().then(() => {
        logger.info("ChatWebsocket 已暴露，连接初始化交由 BOSS 原始脚本执行");
    }).catch(err => {
        logger.error("setChatWebsocket 执行失败:", err);
    });
}

/**
 * Hook existing WebSocket instances by intercepting the send method
 */
let existingPrototypeHookInstalled = false;

function hookExistingWebSockets() {
    if (existingPrototypeHookInstalled) {
        return;
    }
    existingPrototypeHookInstalled = true;
    logRecorder.info("WS Send Hook Start");

    // Patch the original prototype, not only the replacement constructor's
    // prototype. This captures sockets created before the userscript loaded;
    // those sockets are the common reason HR messages stop reaching the AI.
    originalWebSocket.prototype.send = function (data: any) {
        const url = String((this as any)?.url || '').replace(":443", "");
        if (url.includes(TARGET_URL)) {
            hookReceiveForInstance(this);
            if (sendInterceptor) {
                data = sendInterceptor(data);
            }
        }
        return nativeWebSocketSend.call(this, data);
    };
    logRecorder.info("WS Send Hook Success");
}

/**
 * Add receive hook for a specific WebSocket instance
 */
function hookReceiveForInstance(wsInstance: WebSocket) {
    installReceiveListener(wsInstance, data => {
        if (receiveInterceptor) {
            receiveInterceptor(data);
        }
    });
}

/**
 * Initialize the complete WebSocket hooking system
 */
function setupCompleteWebSocketHook() {
    receiveDispatchHookInstalled = installReceiveDispatchHook(
        originalWebSocket.prototype,
        (target, event) => {
            const url = String((target as WebSocket)?.url || '').replace(":443", "");
            if (event?.type !== 'message' || !url.includes(TARGET_URL)) {
                return;
            }
            if (receiveInterceptor) {
                receiveInterceptor(event.data);
            }
        },
    );
    // Hook existing WebSockets (in case some were created before our hook)
    hookExistingWebSockets();
    logRecorder.info("WS Receive Dispatch Hook", receiveDispatchHookInstalled ? "Success" : "Fallback");
}

if (ownsWebSocketHook) {
    setupCompleteWebSocketHook()
}

//======================================================================================================================

// 设置拦截器函数
function setSendInterceptor(interceptor: (data: any) => any) {
    sendInterceptor = interceptor;
}

function setReceiveInterceptor(interceptor: (data: any) => any) {
    receiveInterceptor = interceptor;
}


// 设置发送消息拦截器
if (ownsWebSocketHook) {
setSendInterceptor((data) => {
    logger.trace("发送消息原始数据：", data)
    let wsData: TechwolfChatProtocol = decodeMqttAndProtobuf(data, "发送") as TechwolfChatProtocol
    if (!(wsData && wsData?.messages.length >= 1)) {
        return data;
    }
    let msgText = getMsgBody(wsData);
    let toUid = normalizeNumber(firstMessageOf(wsData)?.to?.uid);
    if (!toUid) {
        return data;
    }

    logger.debug("发送消息解码内容：", msgText)
    if (msgText.endsWith(Tools.getEndChar()) || filter(msgText, wsData, toUid)) {
        // ai消息，不做拦截；或者是投递之后自动发送的自定义招呼语
        return data;
    }

    // 不是ai发送的消息；用户手动介入了
    let bossUserCache = BossOption.getBossUserInfoByCache(toUid);
    if (!bossUserCache) {
        return data;
    }

    // 手动消息如果是启动命令，则启动ai交流，并拦截当前消息
    if (msgText === "start") {
        let bossUserCache = BossOption.getBossUserInfoByCache(toUid);
        if (!bossUserCache) {
            return;
        }
        // 启动ai交流
        AiPower.updateAskStatus(BossOption.buildJobKey(bossUserCache), false)
            .then(resp => logRecorder.info(`[${bossUserCache?.jobTitle}] 命令启动AI交流：${resp.data.data}`))
        return;
    }

    // 不是启动命令，则停止ai交流
    AiPower.updateAskStatus(BossOption.buildJobKey(bossUserCache), true)
        .then(resp => logRecorder.info(`[${bossUserCache?.jobTitle}] 手动介入关闭AI交流：${resp.data.data}`))
    return data;
});
}

// 设置接收消息拦截器
if (ownsWebSocketHook) {
setReceiveInterceptor((data) => {
    logger.trace("接收消息原始数据：", data)
    let wsData: TechwolfChatProtocol = decodeMqttAndProtobuf(data, "接收") as TechwolfChatProtocol
    if (!(wsData && wsData?.messages.length >= 1)) {
        return data;
    }
    for (const singleWsData of splitChatProtocolMessages(wsData)) {
    let msgBody = getMsgBody(singleWsData);
    let fromUid = normalizeNumber(firstMessageOf(singleWsData)?.from?.uid);
    if (!fromUid) {
        continue;
    }
    if (Tools.window._PAGE.uid === fromUid) {
        logger.debug("接收到自己的消息='" + msgBody + "'")
        SendStateTracker.confirmOwnEcho(msgBody, singleWsData);
        continue;
    }

    if (!userStore) {
        userStore = UserStore();
    }

    // 可能存在配置还未加载，导致这里的aiSeatStatus状态不对（消息页面刷新后第一次可能出现）
    // The hook is imported before PlatformFactory starts loading the remote
    // user config. Keep the first HR message until that handoff completes.
    if (!isUserConfigReady()) {
        queueInboundMessage(singleWsData, fromUid, msgBody);
        logger.info("AI配置尚未加载，暂存HR消息等待配置完成");
        continue;
    }
    if (!userStore.user.aiSeatStatus) {
        logger.info("AI坐席未开启结束-前置")
        continue;
    }

    let bossOption: BossOption = new BossOption()
    bossOption.handlerBossMessage(singleWsData, fromUid, msgBody).then();
    }
    return data;
});
}

//  todo 发送图片简历依赖image对象


let userStore: any;
const pendingInboundMessages = new Map<string, {
    wsData: TechwolfChatProtocol;
    fromUid: number;
    msgBody: string;
}>();

function inboundMessageKey(fromUid: number, msgBody: string): string {
    return `${fromUid}:${String(msgBody || '').replace(/\s+/g, ' ').trim()}`;
}

function queueInboundMessage(wsData: TechwolfChatProtocol, fromUid: number, msgBody: string): void {
    const key = inboundMessageKey(fromUid, msgBody);
    if (pendingInboundMessages.has(key)) {
        return;
    }
    pendingInboundMessages.set(key, {wsData, fromUid, msgBody});
    void waitForUserConfigReady().then((ready) => {
        const pending = pendingInboundMessages.get(key);
        if (!pending) {
            return;
        }
        pendingInboundMessages.delete(key);
        if (!ready || !isUserConfigReady()) {
            logger.warn("AI配置加载超时，已跳过本条HR消息，等待后续补扫");
            return;
        }
        if (!UserStore().user.aiSeatStatus) {
            return;
        }
        new BossOption().handlerBossMessage(pending.wsData, pending.fromUid, pending.msgBody).catch((error) => {
            logger.error("延迟处理HR消息失败", error);
        });
    });
}

/**
 * 是否是投递之后自动发送的自定义招呼语以及自定义图片简历
 * @param msg
 * @param wsData
 */
function filter(msg: string, wsData: any, toUid: number): boolean {
    if (SendStateTracker.isTrackedAutomaticOutbound(msg, wsData, toUid)) {
        return true;
    }

    return bodyImageOf(wsData)

}

/**
 * 拦截并修改目标脚本（如 socket.js），将 ChatWebsocket 暴露到 window 对象
 */
async function setChatWebsocket(): Promise<void> {
    logger.info("build ChatWebsocket")
    // 劫持脚本内容
    return fetch("https://static.zhipin.com/assets/zhipin/geek/socket.js?v=20250313")
        .then((res) => res.text())
        .then((code) => {
            // 在代码开头注入所需的全局变量
            let injectedVars = `const __PROTO_FILE_VAR__ = '${protoDefinition}';\n`;

            // 修改代码：在 ChatWebsocket 定义后暴露到 window
            let str = '\nTools.window.ChatWebsocket = ChatWebsocket;\nTools.window.ChatWebsocketImage = ChatWebsocket;\nconsole.log("set ChatWebsocket 成功", ChatWebsocket)\n';
            let modifiedCode = injectedVars + code.replaceAll(/if \(\"EventBus\" in window\) \{\s+EventBus.subscribe\("CHAT_SEND_TEXT".*fail\);\s+}\);\s+}/gs, str);

            try {
                // 使用原生的 Function 执行代码
                new Function("Tools", modifiedCode)(Tools);
                logger.info("window 挂载 ChatWebsocket 成功", Tools.window.ChatWebsocketImage);
            } catch (e) {
                logger.error("执行劫持脚本失败:", e);
            }
            return Promise.resolve();
        })
        .catch((err) => {
            logger.error("setChatWebsocket 捕获到网络或执行异常:", err);
        });
}
