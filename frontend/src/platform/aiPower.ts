import axiosOriginal from "axios";
import axios from "../axios";
import logger from "../logging";
import {Tools} from "./utils";
import {AiFilterDecision, aiFilterAction, compactAiFilterReason, shouldBlockConversationPreflight, shouldRetryAfterAiFilter} from "./filterGate";
import {readFriendSource} from "./friendSource";
import {ConversationPauseRegistry} from "./pauseRegistry";
import {userSnapshotStorageKey} from "../stores/userSnapshotKey";
import {ServerStore} from "../stores/server";

type JobDetailCacheEntry = {
    time: number;
    text: string;
};

export class AiPower {
    public static readonly SEMANTIC_SCREENING_PROMPT =
        "执行服务端岗位语义筛选策略，结合候选人简历、求职要求和完整职位信息判断；明确风险由服务端固定规则识别，信息不足但无明确风险时先聊天确认。";
    private static readonly jobDetailCache: Map<string, JobDetailCacheEntry> = new Map();
    private static readonly filterDecisionCache: Map<string, {time: number; result: AiFilterDecision}> = new Map();
    private static readonly FILTER_DECISION_TTL = 10 * 60 * 1000;

    public static async ask(
        question: string,
        jobKey: string,
        bossUserInfo: any,
        prompt = '',
        platformBodyType?: number,
    ): Promise<any> {
        const jobContext = await this.getJobContextText(bossUserInfo);
        let filterDecision: AiFilterDecision | null = null;
        const hasJobContext = Boolean(String(bossUserInfo?.jobTitle || '').trim() || jobContext.trim());
        if (hasJobContext) {
            try {
                const filterResponse = await this.filterForConversation(
                    bossUserInfo,
                    prompt || this.SEMANTIC_SCREENING_PROMPT,
                );
                filterDecision = filterResponse?.data?.data || null;
            } catch (error: any) {
                // The server repeats this check with the authenticated profile.
                // A client-side timeout must not consume an HR message.
                logger.warn("聊天岗位AI预筛选失败，交服务端二次判定", error?.message || error);
            }
        } else {
            // A missing job card is a browser-context recovery problem, not a
            // safety rejection. Let the server answer the HR message using the
            // resume, preferences, and conversation context it already has.
            logger.warn("聊天暂未读到岗位上下文，跳过岗位闸门并继续普通交流");
        }
        if (hasJobContext && shouldBlockConversationPreflight(filterDecision)) {
            logger.info("聊天岗位AI闸门暂停自动回复", {
                jobKey,
                action: aiFilterAction(filterDecision),
                reason: compactAiFilterReason(filterDecision),
            });
            return {
                data: {
                    data: {
                        answerTypeList: [3],
                        answerContent: "",
                        operationTypeList: [],
                        stopReason: "JOB_GATE",
                        jobFilterDecision: filterDecision,
                    },
                    code: 200,
                    message: "岗位AI闸门暂停自动回复",
                },
            };
        }
        return axios.post("/api/job/seeker/cloned/ask", {
                question,
                jobKey,
                platformBodyType: [1, 4, 8, 12, 14].includes(Number(platformBodyType))
                    ? Number(platformBodyType)
                    : undefined,
                jobInfo: {
                    jobTitle: bossUserInfo.jobTitle,
                    jobContext,
                    aiFilterDecision: filterDecision,
                }
            },
            {
                // 部分 AI 回复较慢。
                timeout: 90000
            })
    }

    public static async greeting(jobInfo: any): Promise<any> {
        return axios.post("/api/job/ai/assistant/generate/greeting", {jobInfo}, {
            timeout: 30000
        })
    }

    public static async getJobContextText(bossUserInfo: any): Promise<string> {
        const parts: string[] = [];
        const add = (text: string) => {
            const value = this.limitText(text, 3500);
            if (value && !parts.some(old => old === value || old.includes(value) || value.includes(old))) {
                parts.push(value);
            }
        };

        add(this.getVisibleJobCardText(bossUserInfo));
        add(await this.fetchJobDetailText(bossUserInfo));
        return this.limitText(parts.join(" | "), 5000);
    }

    private static getVisibleJobCardText(bossUserInfo: any): string {
        try {
            const normalize = (value: any) => this.normalizeText(value);
            const selected = document.querySelector(".friend-content.selected") as any;
            const source = readFriendSource(selected);
            const matchedSource = this.matchesBossSource(source, bossUserInfo) ? source : undefined;
            const parts: string[] = [];
            const add = (label: string, value: any) => {
                const text = normalize(Array.isArray(value) ? value.filter(Boolean).join("、") : value);
                if (text) {
                    parts.push(`${label}:${text}`);
                }
            };

            if (matchedSource) {
                add("公司", matchedSource.brandName || matchedSource.companyName || matchedSource.company);
                add("岗位", matchedSource.jobName || matchedSource.positionName || matchedSource.position || matchedSource.expectPosition);
                add("薪资", matchedSource.salaryDesc || matchedSource.salary || matchedSource.jobSalary);
                add("地点", [
                    matchedSource.cityName,
                    matchedSource.areaDistrict,
                    matchedSource.districtName,
                    matchedSource.businessDistrict,
                    matchedSource.businessDistrictName,
                    matchedSource.address,
                    matchedSource.addressDesc,
                    matchedSource.poiName,
                    matchedSource.locationName,
                ].filter(Boolean).join(""));
                add("经验", matchedSource.jobExperience || matchedSource.experience);
                add("学历", matchedSource.jobDegree || matchedSource.education);
                add("规模", matchedSource.brandScaleName || matchedSource.companyScale || matchedSource.scale);
                add("职位描述", matchedSource.postDescription || matchedSource.jobDescription || matchedSource.description || matchedSource.content);
            }

            if (matchedSource || (!bossUserInfo?.encryptJobId && !bossUserInfo?.securityId)) {
                const selectors = [
                    ".chat-job-card",
                    ".dialog-job-info",
                    ".boss-dialog-job",
                    ".job-detail-card",
                    ".job-detail-content",
                    ".job-detail-box",
                    ".job-detail-section",
                    "[class*='job-detail']",
                    "[class*='position-card']",
                ];
                for (const selector of selectors) {
                    document.querySelectorAll(selector).forEach((element) => {
                        if (!this.isVisibleJobElement(element as HTMLElement)) {
                            return;
                        }
                        const text = normalize((element as HTMLElement).innerText || element.textContent);
                        if (this.isLikelyJobText(text)) {
                            parts.push(text.slice(0, 1600));
                        }
                    });
                }
            }

            if (bossUserInfo?.jobTitle) {
                parts.push(`当前联系人岗位:${normalize(bossUserInfo.jobTitle)}`);
            }
            const unique: string[] = [];
            for (const part of parts) {
                if (part && !unique.some(old => old === part || old.includes(part) || part.includes(old))) {
                    unique.push(part);
                }
                if (unique.length >= 3) {
                    break;
                }
            }
            return unique.join(" | ").slice(0, 5000);
        } catch (e) {
            logger.debug("聊天页职位卡片读取异常", e);
            return "";
        }
    }

    private static async fetchJobDetailText(bossUserInfo: any): Promise<string> {
        try {
            const securityId = bossUserInfo?.securityId;
            const encryptJobId = bossUserInfo?.encryptJobId;
            const lid = bossUserInfo?.lid || "";
            if (!securityId && !encryptJobId) {
                return "";
            }

            const cacheKey = [securityId || "", encryptJobId || "", lid].join(":");
            const now = Date.now();
            const cached = this.jobDetailCache.get(cacheKey);
            if (cached && now - cached.time < 5 * 60 * 1000) {
                return cached.text;
            }

            const urls: string[] = [];
            if (securityId) {
                urls.push(`https://www.zhipin.com/wapi/zpgeek/job/card.json?lid=${encodeURIComponent(lid)}&securityId=${encodeURIComponent(securityId)}&sessionId=`);
            }
            if (securityId && encryptJobId) {
                urls.push(`https://www.zhipin.com/wapi/zpgeek/job/card.json?securityId=${encodeURIComponent(securityId)}&jobId=${encodeURIComponent(encryptJobId)}&sessionId=`);
            }

            for (const url of urls) {
                try {
                    const resp = await axiosOriginal.get(url, {
                        timeout: 5000,
                        headers: {"Zp_token": Tools.getCookieValue("bst")}
                    });
                    const text = this.jobContextFromObject(resp.data);
                    if (text.length > 20) {
                        const result = "接口职位详情：" + text;
                        this.jobDetailCache.set(cacheKey, {time: now, text: result});
                        return result;
                    }
                } catch (e: any) {
                    logger.debug("聊天职位详情接口读取失败", e?.message || e);
                }
            }
        } catch (e: any) {
            logger.debug("聊天职位详情读取异常", e?.message || e);
        }
        return "";
    }

    private static jobContextFromObject(data: any): string {
        const source = data?.zpData || data || {};
        const card = source.jobCard || source.jobInfo || source.jobDetail || source;
        const parts: string[] = [];
        const add = (label: string, value: any) => {
            if (Array.isArray(value)) {
                value = value.filter(Boolean).join("、");
            }
            if (value && typeof value === "object") {
                value = JSON.stringify(value);
            }
            const text = this.normalizeText(value);
            if (text) {
                parts.push(`${label}:${text}`);
            }
        };
        add("岗位", card.jobName || card.positionName || card.position || card.title);
        add("薪资", card.salaryDesc || card.salary || card.jobSalary || card.price);
        add("经验", card.jobExperience || card.experience);
        add("学历", card.jobDegree || card.education);
        add("地点", [card.cityName || card.city, card.areaDistrict, card.businessDistrict, card.address || card.location]
            .filter(Boolean).join(""));
        add("公司", card.brandName || card.companyName || card.company);
        add("行业", card.brandIndustry || card.industry);
        add("规模", card.brandScaleName || card.companyScale || card.scale);
        add("标签", card.jobLabels || card.labels || card.skills || card.welfareList);
        add("职位描述", card.postDescription || card.jobDescription || card.description || card.content);
        add("活跃", card.activeTimeDesc);
        return this.limitText(parts.join(" | "), 3500);
    }

    private static matchesBossSource(source: any, bossUserInfo: any): boolean {
        if (!source) {
            return false;
        }
        const targetJobId = bossUserInfo?.encryptJobId;
        const targetSecurityId = bossUserInfo?.securityId;
        if (!targetJobId && !targetSecurityId) {
            return true;
        }
        const sourceJobId = String(source.encryptJobId || source.jobId || "");
        const sourceSecurityId = String(source.securityId || "");
        return Boolean((targetJobId && sourceJobId === String(targetJobId))
            || (targetSecurityId && sourceSecurityId === String(targetSecurityId)));
    }

    private static isVisibleJobElement(element: HTMLElement): boolean {
        const rect = element.getBoundingClientRect?.();
        const style = window.getComputedStyle?.(element);
        if (!rect || rect.width <= 0 || rect.height <= 0
            || style?.display === "none" || style?.visibility === "hidden") {
            return false;
        }
        return !element.closest(".job-list-container,.job-recommend-result,.job-list,.recommend-search-inner,.label-list,.friend-list,.ai-job-hunting,.ai-job-panel");
    }

    private static isLikelyJobText(text: string): boolean {
        return text.length >= 12 && text.length <= 1600
            && /(查看职位|岗位职责|任职要求|职位描述|工作内容|薪资|经验|学历|工作地点|岗位|职位|\d+\s*[-~～]\s*\d+\s*K)/i.test(text);
    }

    private static normalizeText(value: any): string {
        return String(value || "").replace(/\s+/g, " ").trim();
    }

    private static limitText(value: any, maxLength: number): string {
        const text = this.normalizeText(value);
        return text.length > maxLength ? text.slice(0, maxLength) : text;
    }

    public static async filter(prompt: string, jobBaseInfo: string, jobExtInfo: string): Promise<any> {
        return axios.post("api/job/filter/one", {
                prompt,
                jobBaseInfo,
                jobExtInfo
            },
            {
                timeout: 60000
            })
    }

    public static rememberFilterDecision(jobSource: any, result: AiFilterDecision | null | undefined): void {
        const key = this.filterDecisionKey(jobSource);
        if (!key || !result) {
            return;
        }
        this.filterDecisionCache.set(key, {time: Date.now(), result});
    }

    public static getCachedFilterDecision(jobSource: any): AiFilterDecision | null {
        const key = this.filterDecisionKey(jobSource);
        if (!key) {
            return null;
        }
        const entry = this.filterDecisionCache.get(key);
        if (!entry || Date.now() - entry.time > this.FILTER_DECISION_TTL) {
            this.filterDecisionCache.delete(key);
            return null;
        }
        return entry.result;
    }

    public static async filterForConversation(bossUserInfo: any, prompt: string): Promise<any> {
        const cached = this.getCachedFilterDecision(bossUserInfo);
        if (cached) {
            return {data: {data: cached}};
        }
        const visibleJobCard = await this.getJobContextText(bossUserInfo);
        const response = await this.filter(
            prompt,
            JSON.stringify({jobName: bossUserInfo?.jobTitle || ""}),
            JSON.stringify({postDescription: visibleJobCard}),
        );
        const result = response?.data?.data;
        // 分析失败（可重试）的结果不缓存，否则投递那边 10 分钟内都会直接读到失败。
        if (!shouldRetryAfterAiFilter(result)) {
            this.rememberFilterDecision(bossUserInfo, result);
        }
        return response;
    }

    private static filterDecisionKey(jobSource: any): string {
        if (!jobSource) {
            return "";
        }
        const securityId = String(jobSource.securityId || "").trim();
        const encryptJobId = String(jobSource.encryptJobId || "").trim();
        const lid = String(jobSource.lid || "").trim();
        const ids = [securityId, encryptJobId, lid].filter(Boolean);
        const jobKey = ids.length > 0 ? ids.join(":") : [
            jobSource.jobTitle,
            jobSource.companyName || jobSource.brandName || jobSource.company,
            jobSource.salaryDesc || jobSource.salary || jobSource.jobSalary,
            jobSource.cityName || jobSource.city || jobSource.address,
        ].map(value => String(value || "").replace(/\s+/g, " ").trim())
            .filter(Boolean)
            .join("|");
        if (!jobKey) {
            return "";
        }
        return `${this.filterProfileScope()}::${jobKey}`;
    }

    private static filterProfileScope(): string {
        const accountKey = String(Tools.window?._PAGE?.uid || "").trim() || "anonymous";
        const serverUrl = ServerStore().baseUrl;
        let profile: any = {};
        try {
            const snapshotKey = userSnapshotStorageKey(accountKey, serverUrl);
            profile = snapshotKey ? JSON.parse(localStorage.getItem(snapshotKey) || "{}") : {};
        } catch {
            profile = {};
        }
        // The server uses the current resume and preference. Include their local
        // revisions in the short-lived cache key so a changed profile is never
        // evaluated with an old account's decision.
        const profileVersion = JSON.stringify({
            resumeId: profile?.resumeId || "",
            preference: profile?.preference || {},
        });
        return `${serverUrl}:${accountKey}:${this.shortHash(profileVersion)}`;
    }

    private static shortHash(value: string): string {
        let hash = 2166136261;
        for (let index = 0; index < value.length; index += 1) {
            hash ^= value.charCodeAt(index);
            hash = Math.imul(hash, 16777619);
        }
        return (hash >>> 0).toString(36);
    }

    public static async updateAskStatus(jobKey: string, stop: boolean): Promise<any> {
        // 先作废本地在途回复，再请求服务端；这样即使服务端暂停生效有延迟，
        // 生成中的旧回复也不会在用户暂停之后被补发出去。
        if (stop) {
            ConversationPauseRegistry.pause(jobKey);
        } else {
            ConversationPauseRegistry.resume(jobKey);
        }
        return axios.post(`/api/job/seeker/cloned/change/session/status?jobKey=${jobKey}&stop=${stop}`)
    }

    public static async getSessionStatus(jobKey: string): Promise<any> {
        return axios.get(`/api/job/seeker/cloned/session/status?jobKey=${encodeURIComponent(jobKey)}`)
    }

    public static async getGlobalAiSeatStatus(): Promise<boolean | null> {
        try {
            const response = await axios.post("/api/user/userinfo", {});
            const status = response?.data?.data?.aiSeatStatus;
            return typeof status === "boolean" ? status : null;
        } catch (error: any) {
            logger.warn("【未读补扫】无法确认账号级AI坐席开关，本轮停止", error?.message || error);
            return null;
        }
    }
}
