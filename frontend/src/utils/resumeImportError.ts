import {AxiosError} from "axios";
import type {AxiosResponse} from "axios";

export type ResumeImportStage = "identity" | "lookup" | "download" | "upload";

const notifiedErrors = new WeakSet<object>();

export function markRequestErrorNotified(error: object): void {
    notifiedErrors.add(error);
}

export function applicationResponseError(response: AxiosResponse): AxiosError {
    const message = response.data?.message;
    return new AxiosError(
        typeof message === "string" && message.trim() ? message : "服务端请求失败",
        AxiosError.ERR_BAD_RESPONSE,
        response.config,
        response.request,
        response,
    );
}

export function rateLimitMessage(error: any): string | undefined {
    if (error?.response?.status !== 429 && error?.response?.data?.code !== 429 && error?.status !== 429) {
        return;
    }
    const headers = error?.response?.headers;
    const raw = typeof headers?.get === "function"
        ? headers.get("Retry-After")
        : headers?.["retry-after"] ?? headers?.["Retry-After"];
    const text = typeof raw === "string" || typeof raw === "number" ? String(raw).trim() : "";
    const seconds = text && /^\d+$/.test(text)
        ? Number(text)
        : text ? Math.ceil((Date.parse(text) - Date.now()) / 1000) : NaN;
    const delay = Number.isFinite(seconds) && seconds > 0 ? `等待 ${seconds} 秒后` : "稍后";
    return `请求过于频繁，请${delay}手动重试。`;
}

export function resumeImportErrorNotice(error: any, stage: ResumeImportStage): string | null {
    if (error && typeof error === "object" && notifiedErrors.has(error)) {
        return null;
    }
    const limited = rateLimitMessage(error);
    if (limited) return limited;
    if (error?.response?.data?.code === 2003) {
        return "服务端身份校验失败，简历尚未导入；请确认已登录本人 BOSS 账号。";
    }
    if (error?.code === "RESUME_SCOPE_INVALID") {
        return "当前账号或服务器地址无效，请填写完整的 HTTP(S) 服务地址，并确认 BOSS 登录状态后重试。";
    }
    if (error?.code === "APP_SESSION_NOT_READY") {
        return "AI 工作猎手账号还没有连接成功，简历尚未读取；请刷新 BOSS 页面并等待连接完成后再试。";
    }
    if (stage === "identity") {
        return "未能读取完整的 BOSS 登录身份，请确认已登录本人账号后重试。";
    }
    if (stage === "lookup") {
        return error?.code === "RESUME_ATTACHMENT_MISSING"
            ? "未找到 BOSS 附件简历，请先在 BOSS 个人中心上传本人附件简历。"
            : "读取 BOSS 附件简历信息失败，请确认 BOSS 登录状态后手动重试。";
    }
    if (stage === "download") {
        return "BOSS 附件下载失败或未返回有效文件，请检查附件及 BOSS 登录状态后手动重试。";
    }
    if (error?.code === "ECONNABORTED" || error?.code === "ERR_NETWORK") {
        return "简历导入请求未能完成，保存结果尚未确认；请稍后刷新状态，勿重复上传。";
    }
    return "服务端未能完成简历导入，请稍后重试；不能据此判断 BOSS 附件有误。";
}
