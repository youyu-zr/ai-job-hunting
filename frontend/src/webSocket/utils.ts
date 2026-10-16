import {mqtt} from "./mqtt";
import {protobufType} from "./protobuf"
import logger from "../logging";
import {decodeMessageBatch, getMsgBody} from "./messageBody";

const textDecoder = new TextDecoder();

// 纯函数实现集中在 ./messageBody，这里只做转发，保证线上路径与单测路径一致。
export {decodeMessageBody, decodeMessageBatch, mapMessageBody, getMsgBody} from "./messageBody";

export function decodeMqttAndProtobuf(data: any, caller: string): any {
    let uint8Array = new Uint8Array(data);
    let mqttDecodeResult: any;
    try {
        mqttDecodeResult = mqtt.decode(uint8Array);
        // 建立连接时的信息 or 一般为心跳数据
        if (mqttDecodeResult.topic !== 'chat') {
            logger.trace("过滤【非chat】主题", mqttDecodeResult)
            return;
        }
        let chatProtocolObj: any = protobufType.decode(mqttDecodeResult.payload);
        const messages = chatProtocolObj?.messages;
        if (Array.isArray(messages) && messages.length !== 0) {
            logger.debug(caller + "【消息mqtt】：", mqttDecodeResult)
            logger.debug(caller + "【消息对象】：", chatProtocolObj)
            // boss索要简历,交换电话，交换微信等信息时，body.type=7，逐条补齐正文。
            decodeMessageBatch(messages, textDecoder.decode(mqttDecodeResult.payload), caller);
        } else {
            logger.debug(caller + "【非消息协议对象】", chatProtocolObj)
        }
        return chatProtocolObj;
    } catch (e) {
        // 这里可能是解码失败，打印日志
        logger.debug(caller + " 解码失败", mqttDecodeResult, e)
    }
}

export function normalizeNumber(number: any): number {
    if (typeof number === 'string' || typeof number === 'number') {
        return number as number;
    } else if ('low' in number) {
        return number.toNumber();
    }

    return 0;
}