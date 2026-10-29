import {
    FetchJobBossFailExp,
    NotMatchException,
    PlatformError,
    PublishLimitExp,
    PublishStopExp,
    PushReqException
} from "../exp";
import {scrollElementToBottom, simulateScrollToEnd, TampermonkeyApi, Tools} from "./utils";
import logger from '../logging'
import axiosOriginal from "axios";
import {PushResultStatus, PushStatus} from "../enums";
import {Message, waitForMessageSender} from "../webSocket/protobuf";
import {LogRecorder} from "../logging/record";
import {pushResultCount, UserStore} from "../stores";
import {userRemoteLoad} from "../stores/remote";
import {AiPower} from "./aiPower";
import {SendStateTracker, SendTraceContext} from "./sendState";
import {AiFilterDecision, aiFilterAction, aiFilterBlockReason, compactAiFilterReason, formatAiFilterReasonForDelivery, shouldBlockAfterAiFilter, shouldRetryAfterAiFilter} from "./filterGate";
import {evaluateLocationConsistency, readSearchCity} from "./locationGuard";
import {checkHardConditions, matchesExcludeList} from "./hardConditions";
import {ElMessage} from "../utils/tools";
import {ElNotification} from "element-plus";
import {formatAiFilterSummary, formatDeliverySuccessLog} from "../logging/presentation";
import {extractGreetingText, isUsableJobGreeting} from "./greetingGuard";
import {
    AiFilterProcessingError,
    aiFilterNonRetryableMessage,
    handleAiFilterProcessingError,
    normalizeAiFilterFailure,
} from "./jobFilterFailure";

let pushResultCounter: any;
/** 每天最多成功投递数（BOSS 普通账号每日开聊上限约 100）。 */
const DAILY_PUSH_LIMIT = 80;
let userStore: any;


export enum PlatformTypeEnum {
    Boss,
    // ZhiLian,//智联
    // 前职无忧
    LiePin,
    UnKnow,
}

export interface ElementP {
    el: Element,
    p?: string
}


export interface Platform {
    name: string,
    urlList: string[],
    curUrl: string,

    getMountEle(): Promise<ElementP>;

    getRenderComponent(): any;

    startPush(): Promise<void>;

    pausePush(): void;

    getPlatformType(): PlatformTypeEnum;
}


export abstract class AbsPlatform implements Platform {
    abstract name: string;
    abstract curUrl: string;
    abstract urlList: string[];
    protected logRecorder: LogRecorder = new LogRecorder('recorder');

    protected pushStatus: PushStatus = PushStatus.NOT_START
    protected _pushMock: boolean = false;
    private _selfDefPushCountLimit = 5
    protected locationMismatchCount = 0;
    protected locationGuardConfigKey = '';


    set pushMock(value: boolean) {
        this._pushMock = value;
    }

    set selfDefPushCountLimit(value: number) {
        this._selfDefPushCountLimit = value;
    }

    get selfDefPushCountLimit(): number {
        return this._selfDefPushCountLimit;
    }

    abstract getPlatformType(): PlatformTypeEnum;

    abstract getMountEle(): Promise<ElementP>;

    abstract getRenderComponent(): Promise<any>;

    async startPush() {
        if (this._pushLoopRunning) {
            throw new PublishStopExp("上一轮投递还在收尾，请稍等几秒再点开始");
        }
        this._pushLoopRunning = true;
        try {
            await this.runPushLoop();
        } finally {
            this._pushLoopRunning = false;
        }
    }

    private _pushLoopRunning = false;

    private async runPushLoop() {
        this.logRecorder.info("开始投递")
        // 每次投递前清空单次成功计数器
        pushResultCounter.clearOnceSuccessCount()
        this.pushStatus = PushStatus.PUSHING;
        this.startPreHandler()
        do {
            // 获取jobDetail集合并过滤
            let jobList = this.getJobList();
            for (const jobDetail of jobList) {
                try {
                    this.preMatchJob();
                    await this.matchJob(jobDetail);
                    this.pushPreHandler(jobDetail);
                    const pushResult = await this.push(jobDetail);
                    await this.pushAfterHandler(pushResult, jobDetail);
                } catch (error) {
                    switch (true) {
                        case error instanceof NotMatchException:
                            this.logRecorder.info(`工作【${error.jobTitle}】被过滤 原因：${error.message}${this.formatFilterData(error.data)}`)
                            pushResultCounter.notMatchIncr()
                            break;

                        case error instanceof PushReqException:
                            this.logRecorder.warn(`工作【${error.jobTitle}】投递失败 原因：${error.message}`)
                            pushResultCounter.failIncr()
                            break

                        case error instanceof FetchJobBossFailExp:
                            this.logRecorder.warn(`工作【${error.jobTitle}】发送自定义招呼语失败 原因：${error.message}`)
                            break

                        // 投递停止；手动停止.结束链路
                        case error instanceof PublishStopExp:
                            this.logRecorder.info("手动暂停投递 " + error.message)
                            return;
                        // 投递限制；平台限制.结束链路
                        case error instanceof PublishLimitExp:
                            this.logRecorder.info("停止投递 " + error.message)
                            if (error.message !== "自定义投递次数限制") {
                                ElMessage({message: "已停止投递：" + error.message, type: 'warning', duration: 0, showClose: true})
                            }
                            return;

                        default:
                            const nonRetryableMessage = aiFilterNonRetryableMessage(error);
                            if (nonRetryableMessage) {
                                this.logRecorder.warn(nonRetryableMessage);
                                return;
                            }
                            if (!handleAiFilterProcessingError(error, () => this.getJobKey(jobDetail), {
                                warn: message => this.logRecorder.warn(message),
                                notify: notification => ElMessage(notification),
                            })) {
                                logger.error("未捕获异常--->", error)
                            }
                    }
                }
            }
        } while (await this.next())
        this.logRecorder.info("结束投递")
    }

    next = async () => {
        let next = this.hasNext();
        if (!next) {
            this.logRecorder.info("无下一页数据")
            return false;
        }
        await Tools.sleep(userStore.user.preference.npi * 1000)
        this.acquireDataPre();
        await Tools.sleep(3000)
        return next
    };

    pausePush(): void {
    }

    abstract hasNext(): boolean;

    abstract acquireDataPre(): void;

    abstract startPreHandler(): void;
    abstract getJobList(): JobDetail[];

    abstract matchJob(jobDetail: JobDetail): Promise<boolean>;

    abstract pushPreHandler(jobDetail: JobDetail): JobDetail;

    preMatchJob(): void {
        // 投递前检查，避免无意义的匹配过滤
        if (this._selfDefPushCountLimit !== -1 && pushResultCounter.onceSuccessCount >= this._selfDefPushCountLimit) {
            throw new PublishLimitExp("自定义投递次数限制")
        }
        if (this.pushStatus == PushStatus.PAUSE) {
            throw new PublishStopExp("手动暂停投递")
        }
    }

    async push(jobDetail: JobDetail): Promise<PushResult> {
        if (this.pushStatus == PushStatus.PAUSE) {
            throw new PublishStopExp("手动暂停投递")
        }

        if (this._selfDefPushCountLimit !== -1 && pushResultCounter.onceSuccessCount >= this._selfDefPushCountLimit) {
            throw new PublishLimitExp("自定义投递次数限制")
        }

        // 检查投递限制
        let limitResult = this.isLimit(jobDetail);
        if (limitResult.limit) {
            throw new PublishLimitExp(limitResult.msg)
        }

        if (this._pushMock) {
            let jobTitle = this.getJobKey(jobDetail);
            logger.debug("mock投递 ", jobTitle)
            return {
                message: 'Success',
                code: 0
            }
        }
        return await this.doPush(jobDetail);
    }

    isLimit(jobDetail: JobDetail): { limit: boolean, msg: string } {
        return {
            limit: false,
            msg: this.getJobKey(jobDetail)
        }
    }

    abstract doPush(jobDetail: JobDetail): Promise<any>;

    abstract pushAfterHandler(pushResult: PushResult, jobDetail: JobDetail): Promise<any> ;

    abstract getJobKey(jobDetail: JobDetail): string;

    protected formatFilterData(data: unknown): string {
        if (data === undefined || data === null || data === '') {
            return '';
        }
        const summary = formatAiFilterSummary(data);
        return summary ? `；${summary}` : '';
    }

    getFistJobDetail(): JobDetail {
        return this.getJobList()[0]
    }
}


class BossPlatform extends AbsPlatform {
    curUrl: string;
    name = "Boss";
    urlList = ["/web/geek", "overseas"];
    lastHeight = 0;
    private jobDetailExtCache: Map<string, any> = new Map();
    private preparedGreetingCache: Map<string, string> = new Map();
    private bossGreetingDisablePromise: Promise<void> | null = null;
    private bossAutomaticGreetingDisabled = false;


    constructor(curUrl: string) {
        super();
        this.curUrl = curUrl;
    }

    getPlatformType(): PlatformTypeEnum {
        return PlatformTypeEnum.Boss;
    }


    getMountEle(): Promise<ElementP> {
        return new Promise<ElementP>((resolve) => {
            let count: number = 0;
            const maxCount = 80;
            let interval = setInterval(() => {
                const currentUrl = window.location.href || this.curUrl;
                let element: Element | null = null;
                let p = "";
                if (currentUrl.includes("www.zhipin.com/web/geek/chat")) {
                    element = document.querySelector(".chat-conversation");
                }
                if (currentUrl.includes("www.zhipin.com/web/geek/job-recommend")) {
                    element = document.querySelector(".recommend-search-inner");
                    // element = document.querySelector(".recommend-result-inner");
                    p = "end";
                }
                if (currentUrl.includes("www.zhipin.com/web/geek/jobs")) {
                    element = document.querySelector(".job-recommend-result");
                    // p = "end";
                } else if (currentUrl.includes("www.zhipin.com/web/geek/job")) {
                    element = document.querySelector(".page-job-inner");
                }

                if (currentUrl.includes("overseas")) {
                    element = document.querySelector(".mod-header");
                }

                if (element !== null) {
                    clearInterval(interval);
                    return resolve({
                        el: element as Element,
                        p: p
                    })
                }
                if (count >= maxCount) {
                    clearInterval(interval);
                    logger.error(PlatformTypeEnum.Boss, "获取平台挂载元素失败，回退到 body")
                    return resolve({
                        el: document.body,
                        p: "end"
                    })
                }
                count++;
            }, 300);
        })
    }


    async getRenderComponent(): Promise<any> {
        if (this.curUrl.includes("www.zhipin.com/web/geek/chat")) {
            let promise = import('../components/ui/BossMessage.vue');
            return promise.then(item => item.default)
        }
        if (this.curUrl.includes("www.zhipin.com/web/geek/job") || this.curUrl.includes("overseas")) {
            let promise = import('../components/ui/BossJobList.vue');
            return promise.then(item => item.default)
        }

    }

    startPreHandler(): void {
        this.lastHeight = 0;
        this.preparedGreetingCache.clear();
        this.bossAutomaticGreetingDisabled = false;
    }

    getJobList(): BossJobDetail[] {
        // boss 推荐职位页面 或者jobs页面
        if (this.curUrl.includes("jobs")) {
            let elementNodeList = document.querySelectorAll<any>(".job-card-wrap");
            let jobList = Array.from(elementNodeList).map(item => item.__vue__.data).filter(job => !job.processed) as BossJobDetail[];
            if (elementNodeList.length != 0 && jobList.length == 0) {
                this.logRecorder.info("当前筛选条件下岗位均已投递")
            }
            return jobList;
        }
        if (this.curUrl.includes("job-recommend")) {
            let elementNodeList = document.querySelectorAll<any>(".job-card-wrap");
            return Array.from(elementNodeList).map(item => item.__vue__.data).filter(job => !job.contact) as BossJobDetail[];
        }
        if (this.curUrl.includes("overseas")) {
            let elementNodeList = document.querySelectorAll<any>(".job-card-box");
            return Array.from(elementNodeList).map(item => item.__vue__.data).filter(job => !job.contact) as BossJobDetail[];
        }
        let elementNodeList = document.querySelectorAll<any>(".job-card-wrapper");
        return Array.from(elementNodeList).map(item => item.__vue__.data) as BossJobDetail[];
    }

    hasNext(): boolean {
        logger.debug("hasNext")
        if (this.curUrl.includes("jobs")) {
            return this.lastHeight != document.querySelector(".job-list-container")?.scrollHeight
        }
        if (this.curUrl.includes("overseas")) {
            return this.lastHeight != document.querySelector(".job-list")?.scrollHeight
        }
        if (this.curUrl.includes("job-recommend")) {
            return !!document.querySelector("#footer");
        }
        let nextPageBtn = document.querySelector(".ui-icon-arrow-right") as any;
        if (nextPageBtn === null) {
            return false;
        }
        return nextPageBtn.parentElement.className !== "disabled";
    }

    acquireDataPre(): void {
        // 在等待下一页时点击了停止，不继续获取下一页数据
        if (this.pushStatus == PushStatus.PAUSE) {
            return;
        }
        if (this.curUrl.includes("jobs")) {
            this.lastHeight = document.querySelector(".job-list-container")?.scrollHeight as number
            simulateScrollToEnd().then(() => {
                logger.info("获取下一页成功")
            }).catch(e => {
                this.logRecorder.warn("获取下一页失败", e)
            })
            return;
        } else if (this.curUrl.includes("job-recommend")) {
            simulateScrollToEnd().then(() => {
                logger.info("获取下一页成功")
            }).catch(e => {
                this.logRecorder.warn("获取下一页失败", e)
            })
            return;
        }else if (this.curUrl.includes("overseas")) {
            this.lastHeight = document.querySelector(".job-list")?.scrollHeight as number
            simulateScrollToEnd().then(() => {
                logger.info("获取下一页成功")
            }).catch(e => {
                this.logRecorder.warn("获取下一页失败", e)
            })
            return;
        }
        // 点击下一页
        document.querySelector<any>(".ui-icon-arrow-right").click();
    }


    async matchJob(jobDetail: BossJobDetail) {
        // 标记为已处理
        jobDetail.processed = true
        const jobTitle = this.getJobKey(jobDetail)
        const semanticScreeningEnabled = Boolean(userStore.user.preference.afE)
        this.checkLocationConsistency(jobDetail, jobTitle)
        // 已经沟通过
        if (jobDetail.contact) {
            throw new NotMatchException(jobTitle, jobDetail.contact, '已经沟通过')
        }
        // 过滤猎头
        if (userStore.user.preference.fhE && jobDetail.goldHunter === 1) {
            throw new NotMatchException(jobTitle, jobDetail.goldHunter, '过滤猎头')
        }
        // 仅投递在线boss
        if (userStore.user.preference.polE && !jobDetail.bossOnline) {
            throw new NotMatchException(jobTitle, jobDetail.bossOnline, '仅投递在线boss')
        }

        // 硬条件（不投的公司、坚决不做、薪资底线）不依赖 AI，任何模式下都先查，查到就不花 AI 调用。
        const hardHit = checkHardConditions(userStore.user.preference, jobDetail, {semanticScreeningEnabled});
        if (hardHit) {
            throw new NotMatchException(jobTitle, hardHit.data, hardHit.reason)
        }
        // 「公司名排除」「工作名排除」是用户亲手勾选的黑名单，语义模式下也执行（以前只在关闭 AI 过滤时生效，勾了却没用）。
        // 改为单向匹配：页面名字包含词条才算；页面名字为空、词条只有一个字时不算。
        if (userStore.user.preference.cneE && matchesExcludeList(userStore.user.preference.cne, jobDetail.brandName)) {
            throw new NotMatchException(jobTitle, jobDetail.brandName, '满足排除公司名（偏好设置 → 高级筛选）')
        }
        // 工作名排除和「坚决不做」一样：开着 AI 时字面匹配会误杀「售前技术支持(非销售)」这类岗位，只在关闭 AI 时执行。
        if (!semanticScreeningEnabled && userStore.user.preference.jneE
            && matchesExcludeList(userStore.user.preference.jne, jobDetail.jobName)) {
            throw new NotMatchException(jobTitle, jobDetail.jobName, '满足排除工作名（偏好设置 → 高级筛选）')
        }

        // In semantic mode the complete job, company, salary and requirements are
        // sent to the server AI. Legacy include fields and ranges stay available for
        // users who explicitly disable semantic screening.
        if (!semanticScreeningEnabled) {
            const companyNameInclude: string[] = userStore.user.preference.cni;
            if (userStore.user.preference.cniE && !Tools.fuzzyMatch(companyNameInclude, jobDetail.brandName, true)) {
                throw new NotMatchException(jobTitle, jobDetail.brandName, '不满足配置公司名（偏好设置 → 高级筛选）')
            }

            const jobNameInclude: string[] = userStore.user.preference.jni;
            if (userStore.user.preference.jniE && !Tools.fuzzyMatch(jobNameInclude, jobDetail.jobName, true)) {
                throw new NotMatchException(jobTitle, jobDetail.jobName, '不满足配置工作名（偏好设置 → 高级筛选）')
            }

            const pageSalaryRange = jobDetail.salaryDesc;
            if (userStore.user.preference.srE) {
                if (!Tools.isSalaryRangeInputValid(userStore.user.preference.sr)) {
                    throw new NotMatchException(jobTitle, userStore.user.preference.sr, '高级筛选里的薪资范围格式错误，请使用如8-12、12以上或20以下')
                }
                if (!Tools.isRangeOverlap(userStore.user.preference.sr, pageSalaryRange)) {
                    throw new NotMatchException(jobTitle, pageSalaryRange, '不满足薪资范围（偏好设置 → 高级筛选）')
                }
            }

            const pageCompanyScaleRange = userStore.user.preference.csr;
            if (userStore.user.preference.csrE && !Tools.isRangeOverlap(pageCompanyScaleRange, jobDetail.brandScaleName)) {
                throw new NotMatchException(jobTitle, jobDetail.brandScaleName, '不满足公司规模范围（偏好设置 → 高级筛选）')
            }
        }

        // 通过接口获取工作详情扩展信息
        let jobDetailExt = await this.obtainBossJobDetailExt(jobDetail);
        this.jobDetailExtCache.set(jobTitle, jobDetailExt);
        logger.debug(`获取工作【${jobTitle}】详情扩展信息用于过滤 `, jobDetail)

        //  活跃度
        let activeTimeDesc = jobDetailExt.activeTimeDesc;
        if (!this.bossIsActive(activeTimeDesc)) {
            throw new NotMatchException(jobTitle, activeTimeDesc, '不满足活跃度检查')
        }
        // 详情接口的沟通状态比列表页新；在调用 AI 之前查，已经沟通过的岗位不再花一次 AI 筛选。
        if (this.isCommunication(jobDetailExt)) {
            throw new NotMatchException(jobTitle, jobDetailExt.friendStatus, '已经沟通过')
        }

        let filterResult: AiFilterDecision | null = null;
        // AI is the primary semantic gate. Personal content keyword lists are
        // only used when this optional AI gate is explicitly disabled.
        if (semanticScreeningEnabled) {
            // 同一岗位刚筛过（比如上一轮因网络中断）就复用结果，不重复调用。
            const cachedDecision = AiPower.getCachedFilterDecision(jobDetail);
            const fromCache = Boolean(cachedDecision) && !shouldRetryAfterAiFilter(cachedDecision);
            filterResult = fromCache ? cachedDecision : null;
            if (!fromCache) {
                const screeningPrompt = this.buildSemanticScreeningPrompt();
                let filterResp;
                try {
                    filterResp = await AiPower.filter(
                        screeningPrompt,
                        JSON.stringify(this.unpackBaseInfo(jobDetail)),
                        JSON.stringify(this.unpackExtInfo(jobDetailExt)),
                    );
                } catch (error) {
                    jobDetail.processed = false;
                    throw normalizeAiFilterFailure(error);
                }
                filterResult = filterResp ? filterResp?.data?.data : null;
            }
            if (shouldRetryAfterAiFilter(filterResult)) {
                jobDetail.processed = false;
                throw new AiFilterProcessingError();
            }
            if (!fromCache) {
                // 只记新结果：缓存命中时不再写一次，免得有效期被一直往后推。
                AiPower.rememberFilterDecision(jobDetail, filterResult);
            }
            this.showAiJobDecision(jobDetail, jobDetailExt, filterResult);
            if (shouldBlockAfterAiFilter(filterResult)) {
                throw new NotMatchException(jobTitle, filterResult, aiFilterBlockReason(filterResult))
            }
        } else {
            // Preserve the original preference-driven behavior for users who
            // deliberately turn off semantic AI filtering.
            const jobContent = jobDetailExt.postDescription;
            const jobContentExclude: string[] = userStore.user.preference.jce;
            if (userStore.user.preference.jceE && Tools.fuzzyMatch(jobContentExclude, jobContent, false)) {
                throw new NotMatchException(jobTitle, jobContent, '满足排除工作内容（偏好设置 → 高级筛选）')
            }
            const jobContentInclude: string[] = userStore.user.preference.jci;
            if (userStore.user.preference.jciE && !Tools.fuzzyMatch(jobContentInclude, jobContent, true)) {
                throw new NotMatchException(jobTitle, jobContent, '不满足工作内容（偏好设置 → 高级筛选）')
            }
        }

        // 重新检测投递（页面点击投递后，页面数据不会变，所有标签中获取到的是否沟通过有可能是旧的，需要重新校验）
        if (this.isCommunication(jobDetailExt)) {
            throw new NotMatchException(jobTitle, jobDetailExt.friendStatus, '已经沟通过')
        }

        return true;
    }

    private checkLocationConsistency(jobDetail: BossJobDetail, jobTitle: string): void {
        const preference = userStore.user.preference || {};
        const targetCities = Array.isArray(preference.targetCities) ? preference.targetCities : [];
        const resumeCity = String(preference.resumeCity || '').trim();
        const configKey = `${resumeCity}|${targetCities.join('|')}|${preference.locationMismatchThreshold || 2}`;
        if (configKey !== this.locationGuardConfigKey) {
            this.locationGuardConfigKey = configKey;
            this.locationMismatchCount = Number(TampermonkeyApi.GmGetValue(
                `locationMismatchCount:${configKey}`,
                0,
            )) || 0;
        }

        const result = evaluateLocationConsistency(
            {
                searchCity: readSearchCity(Tools.window?.document || document),
                jobCity: [jobDetail.cityName, jobDetail.areaDistrict, jobDetail.businessDistrict]
                    .filter(Boolean)
                    .join(''),
            },
            {
                enabled: Boolean(preference.locationGuardE),
                resumeCity,
                targetCities,
                mismatchThreshold: preference.locationMismatchThreshold,
            },
            this.locationMismatchCount,
        );

        this.locationMismatchCount = result.nextMismatchCount;
        TampermonkeyApi.GmSetValue(`locationMismatchCount:${configKey}`, this.locationMismatchCount);

        if (result.action === 'WARN') {
            this.logRecorder.warn(`地区一致性提醒【${jobTitle}】：${result.reason}`);
            ElMessage({
                type: 'warning',
                message: result.reason,
                duration: 3500,
            });
            return;
        }
        if (result.action === 'PAUSE') {
            this.pausePush();
            this.logRecorder.error(`地区一致性保护已暂停投递【${jobTitle}】：${result.reason}`);
            ElMessage({
                type: 'error',
                message: `已暂停投递：${result.reason}`,
                duration: 5000,
            });
            throw new NotMatchException(jobTitle, result.reason, '地区一致性保护暂停投递');
        }
    }

    unpackBaseInfo(jobDetail: BossJobDetail): {} {
        return {
            jobName: jobDetail.jobName,
            salaryDesc: jobDetail.salaryDesc,
            jobLabels: jobDetail.jobLabels,
            skills: jobDetail.skills,
            jobExperience: jobDetail.jobExperience,
            jobDegree: jobDetail.jobDegree,
            cityName: jobDetail.cityName,
            areaDistrict: jobDetail.areaDistrict,
            businessDistrict: jobDetail.businessDistrict,
            brandName: jobDetail.brandName,
            brandStageName: jobDetail.brandStageName,
            brandIndustry: jobDetail.brandIndustry,
            brandScaleName: jobDetail.brandScaleName,
            welfareList: jobDetail.welfareList,
        }
    }
    unpackExtInfo(jobDetailExt: any): {} {
        return {
            postDescription: jobDetailExt.postDescription,
            address: jobDetailExt.address,
            activeTimeDesc: jobDetailExt.activeTimeDesc
        }
    }

    private buildSemanticScreeningPrompt(): string {
        return `${AiPower.SEMANTIC_SCREENING_PROMPT}不要把筛选条件直接带入HR回复。`;
    }

    private showAiJobDecision(jobDetail: BossJobDetail, jobDetailExt: any, result: AiFilterDecision | null): void {
        const action = aiFilterAction(result);
        const missing: string[] = [];
        if (!jobDetail.brandScaleName) missing.push('公司规模');
        if (!jobDetailExt?.postDescription) missing.push('职位描述');
        if (!jobDetail.cityName && !jobDetailExt?.address) missing.push('地点');
        const signals = Array.isArray(result?.riskSignals) ? result?.riskSignals?.slice(0, 2).join('、') : '';
        const reason = compactAiFilterReason(result);
        const requirement = signals || (Array.isArray(result?.prepSkills) ? result?.prepSkills?.slice(0, 2).join('、') : '') || '无额外要求';
        const html = [
            `<div><b>岗位：</b>${this.escapeNoticeHtml(jobDetail.jobName)}</div>`,
            `<div><b>公司：</b>${this.escapeNoticeHtml(jobDetail.brandName)}</div>`,
            `<div><b>薪资：</b>${this.escapeNoticeHtml(jobDetail.salaryDesc)}</div>`,
            `<div><b>地点：</b>${this.escapeNoticeHtml([jobDetail.cityName, jobDetail.areaDistrict, jobDetail.businessDistrict].filter(Boolean).join(''))}</div>`,
            `<div><b>AI结论：</b>${this.escapeNoticeHtml(action)}</div>`,
            `<div><b>原因：</b>${this.escapeNoticeHtml(reason)}</div>`,
            `<div><b>关键信息：</b>${this.escapeNoticeHtml(requirement)}</div>`,
            missing.length ? `<div><b>信息缺口：</b>${this.escapeNoticeHtml(missing.join('、'))}</div>` : '',
        ].filter(Boolean).join('');
        ElNotification({
            type: action === 'FILTER' ? 'warning' : 'info',
            title: 'AI岗位筛选',
            message: html,
            dangerouslyUseHTMLString: true,
            duration: 6000,
            showClose: true,
        });
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

    pausePush() {
        this.pushStatus = PushStatus.PAUSE
    }

    getJobKey(jobDetail: BossJobDetail): string {
        return jobDetail.jobName + "-" + jobDetail.cityName + jobDetail.areaDistrict + jobDetail.businessDistrict;
    }


    isLimit(jobDetail: JobDetail): { limit: boolean; msg: string } {
        // 按今天累计成功数判断（successIncr 已按天持久化），反复点「开始」也不会绕过。
        const todaySuccess = Number(TampermonkeyApi.GmGetValue(TampermonkeyApi.PUSH_SUCCESS_COUNT, 0)) || 0;
        return {
            limit: todaySuccess >= DAILY_PUSH_LIMIT,
            msg: `今天已投递 ${todaySuccess} 个，达到每日上限 ${DAILY_PUSH_LIMIT}，明天再继续`
        }
    }

    async doPush(jobDetail: BossJobDetail, errorMsg = '', retries = 3): Promise<any> {
        const jobTitle = this.getJobKey(jobDetail)

        if (retries === 3) {
            logger.debug("正在投递：" + jobTitle)
        }

        // 重试结束；抛出异常结束
        if (retries === 0) {
            this.preparedGreetingCache.delete(jobTitle);
            throw new PushReqException(jobTitle, errorMsg)
        }

        // BOSS sends its own greeting while creating the conversation. Prepare
        // the targeted message and disable that account-level greeting first;
        // otherwise the generic platform text wins the first-message race.
        try {
            await this.prepareJobGreeting(jobDetail);
            await this.ensureBossAutomaticGreetingDisabled();
        } catch (error: any) {
            this.preparedGreetingCache.delete(jobTitle);
            if (error instanceof PushReqException) {
                throw error;
            }
            throw new PushReqException(jobTitle, error?.message || '岗位化招呼语发送前检查失败');
        }

        // 投递请求url
        let publishUrl = `https://www.zhipin.com/wapi/zpgeek/friend/add.json?securityId=` +
            `${jobDetail.securityId}&jobId=${jobDetail.encryptJobId}&lid=${jobDetail.lid}`

        let pushResp: any = {code: PushResultStatus.NOT_START, message: ""};
        try {
            // 避免频繁，每次投递前延时
            await Tools.sleep(userStore.user.preference.pi * 1000 + Math.floor(Math.random() * 2000))
            pushResp = await axiosOriginal.post(publishUrl, null, {headers: {"Zp_token": Tools.getCookieValue("bst")}});
        } catch (error: any) {
            // 重试投递
            logger.debug(`工作【${jobTitle}】投递失败; 正在等待重试; 原因：${error.message}`)
            await Tools.sleep(800);
            return await this.doPush(jobDetail, error.message, retries - 1);
        }

        if (pushResp.data.code === PushResultStatus.FAIL && pushResp.data?.zpData?.bizData?.chatRemindDialog?.content) {
            const remindContent = String(pushResp.data.zpData.bizData.chatRemindDialog.content);
            // 过滤开聊提示，实际投递成功
            if (remindContent.includes("您今天已与120位BOSS沟通")) {
                logger.debug(`当天已投递超过120次 工作【${jobTitle}】已修正为投递成功`)
                return {
                    code: PushResultStatus.SUCCESS,
                    message: "Success"
                }
            }
            // BOSS 弹出了限制/提醒（上限、频繁、需完善简历等）：整轮停下，交给用户本人处理，避免继续请求触发风控。
            throw new PublishLimitExp("BOSS 提示：" + remindContent)
        }
        // 避免频繁
        await Tools.sleep(800);
        return pushResp.data;
    }

    private bossDataCache: Map<string, any> = new Map();

    private async requestBossDataByCache(jobDetail: BossJobDetail): Promise<any> {
        let cacheKey = `${jobDetail.encryptBossId}-${jobDetail.securityId}`;

        // 先检查缓存中是否有数据
        if (this.bossDataCache.has(cacheKey)) {
            return this.bossDataCache.get(cacheKey);
        }

        // 缓存请求结果
        const result = await this.requestBossData(jobDetail);
        this.bossDataCache.set(cacheKey, result);
        return result;
    }

    async requestBossData(jobDetail: BossJobDetail, errorMsg: string = "", retries = 3): Promise<any> {
        let jobTitle = this.getJobKey(jobDetail);

        if (retries === 0) {
            throw new FetchJobBossFailExp(jobTitle, errorMsg || "获取boss数据重试多次失败");
        }
        const url = "https://www.zhipin.com/wapi/zpchat/geek/getBossData";
        const token = Tools.getCookieValue("bst");
        if (!token) {
            throw new FetchJobBossFailExp(jobTitle, "未获取到zp-token");
        }

        const data = new FormData();
        data.append("bossId", jobDetail.encryptBossId);
        data.append("securityId", jobDetail.securityId);
        data.append("bossSrc", "0");

        let resp: any;
        try {
            resp = await axiosOriginal({url, data: data, method: "POST", headers: {Zp_token: token}});
        } catch (e: any) {
            return this.requestBossData(jobDetail, e.message, retries - 1);
        }

        if (resp.data.code !== 0) {
            throw new FetchJobBossFailExp(jobTitle, resp.data.message);
        }
        return resp.data.zpData
    }


    async pushAfterHandler(pushResult: PushResult, jobDetail: BossJobDetail): Promise<any> {
        const jobTitle = this.getJobKey(jobDetail)

        if (pushResult.message === 'Success' && pushResult.code === 0) {
            pushResultCounter.successIncr()
            const semanticScreeningEnabled = Boolean(userStore.user.preference.afE)
            const filterDecision = semanticScreeningEnabled
                ? AiPower.getCachedFilterDecision(jobDetail)
                : null;
            const deliveryReason = filterDecision
                ? formatAiFilterReasonForDelivery(filterDecision)
                : '通过当前筛选条件';
            this.logRecorder.info(formatDeliverySuccessLog({
                companyName: jobDetail.brandName,
                jobName: jobDetail.jobName,
                salaryDesc: jobDetail.salaryDesc,
                location: [
                    jobDetail.cityName,
                    jobDetail.areaDistrict,
                    jobDetail.businessDistrict,
                ].filter(Boolean).join('') || this.jobDetailExtCache.get(jobTitle)?.address,
                reason: deliveryReason,
            }))

            try {
                // Send the targeted text before any optional attachment.
                await this.pushAfterSendMsg(jobDetail, this.preparedGreetingCache.get(jobTitle) || '');
            } catch (e) {
                logger.warn(`工作【${jobTitle}】岗位化招呼语发送失败`, e)
            }
            try {
                // 投递后发送自定义图片
                await this.pushAfterSendImage(jobDetail);
            } catch (e) {
            }
            this.preparedGreetingCache.delete(jobTitle);

            // 标记为已沟通，在推荐页面中下一页会获取之前的数据，所以需要标记为已沟通
            jobDetail.contact = true
            return jobDetail
        }

        if (pushResult.message.includes("今日沟通人数已达上限")) {
            throw new PublishLimitExp(pushResult.message)
        }
        throw new PushReqException(jobTitle, pushResult.message)
    }

    /**
     * 投递后发送自定义消息
     */
    async pushAfterSendMsg(jobDetail: BossJobDetail, preparedGreeting: string) {
        if (this._pushMock || !preparedGreeting) {
            return;
        }
        const customGreeting = preparedGreeting;
        const bossData = await this.requestBossDataByCache(jobDetail);
        // 发送websocket消息
        const trace: SendTraceContext = {
            action: 'POST_DELIVERY_GREETING',
            channel: 'post-delivery',
            bossId: bossData.data.bossId,
            jobKey: this.getJobKey(jobDetail),
            jobTitle: jobDetail.jobName,
            content: customGreeting,
        };
        SendStateTracker.record(trace, 'GENERATED', 'post delivery greeting generated');
        if (!await waitForMessageSender()) {
            SendStateTracker.record(trace, 'FAILED', 'message sender unavailable after waiting');
            throw new Error('岗位化招呼语发送失败：消息通道未就绪');
        }
        let message = new Message({
            form_uid: Tools.window._PAGE.uid.toString(),
            to_uid: bossData.data.bossId.toString(),
            to_name: jobDetail.encryptBossId,
            content: customGreeting,
            image: undefined,
            trace,
            recipient: {
                uid: bossData.data.bossId,
                encryptUid: bossData.data.encryptBossId || jobDetail.encryptBossId,
                friendSource: bossData.data.friendSource || 0,
                securityId: jobDetail.securityId,
                encryptJobId: jobDetail.encryptJobId,
                jobTitle: this.getJobKey(jobDetail),
            },
        });
        const sent = await message.sendAndConfirm();
        if (!sent) {
            throw new Error('岗位化招呼语发送失败：平台未确认消息已发出')
        }
    }

    /** Generate the message before BOSS creates the conversation. */
    private async prepareJobGreeting(jobDetail: BossJobDetail): Promise<string> {
        const jobKey = this.getJobKey(jobDetail);
        const cached = this.preparedGreetingCache.get(jobKey);
        if (cached) {
            return cached;
        }

        let customGreeting = "";
        const jobDetailExt = this.jobDetailExtCache.get(this.getJobKey(jobDetail)) || {};
        const greetingJobInfo = {
            ...this.unpackBaseInfo(jobDetail),
            ...this.unpackExtInfo(jobDetailExt),
            jobTitle: jobDetail.jobName,
            companyName: jobDetail.brandName,
            jobKey: this.getJobKey(jobDetail),
            greetingHint: String(userStore.user.preference.cg || '').trim(),
        };
        for (let attempt = 1; attempt <= 2 && !customGreeting; attempt += 1) {
            try {
                const greetingResp = await AiPower.greeting(greetingJobInfo);
                customGreeting = extractGreetingText(greetingResp);
                if (customGreeting && !isUsableJobGreeting(customGreeting, greetingJobInfo)) {
                    logger.warn(`工作【${this.getJobKey(jobDetail)}】第${attempt}次岗位化招呼语未通过岗位化校验`);
                    customGreeting = "";
                }
            } catch (e: any) {
                logger.warn(`工作【${this.getJobKey(jobDetail)}】第${attempt}次岗位化招呼语生成失败`, e?.message || e);
            }
        }
        if (!customGreeting) {
            const message = `工作【${this.getJobKey(jobDetail)}】招呼语生成失败，已重试一次，未发送招呼语。`;
            logger.warn(message);
            ElMessage({type: 'warning', message, duration: 5000});
            throw new PushReqException(jobKey, '岗位化招呼语生成失败，未投递')
        }
        this.preparedGreetingCache.set(jobKey, customGreeting);
        return customGreeting;
    }

    /**
     * BOSS's friend/add endpoint does not accept a first-message override.
     * Its account-level greeting must therefore be disabled and verified
     * before friend/add is called.
     */
    private async ensureBossAutomaticGreetingDisabled(): Promise<void> {
        if (this.bossAutomaticGreetingDisabled) {
            return;
        }
        if (this.bossGreetingDisablePromise) {
            return this.bossGreetingDisablePromise;
        }
        this.bossGreetingDisablePromise = (async () => {
            const token = Tools.getCookieValue("bst");
            if (!token) {
                throw new Error('未获取到BOSS登录令牌，无法关闭平台默认招呼语');
            }
            const getUrl = 'https://www.zhipin.com/wapi/zpchat/greeting/getGreetingList';
            const getResponse = await axiosOriginal.get(getUrl, {
                headers: {Zp_token: token},
            });
            if (getResponse.data?.code !== 0) {
                throw new Error(getResponse.data?.message || '读取BOSS招呼语设置失败');
            }
            const currentGreeting = getResponse.data?.zpData?.greeting || {};
            if (Number(currentGreeting.status) === 1) {
                const form = new URLSearchParams();
                form.set('status', '0');
                form.set('templateId', String(currentGreeting.templateId || ''));
                const updateResponse = await axiosOriginal.post(
                    'https://www.zhipin.com/wapi/zpchat/greeting/updateGreetingV2',
                    form,
                    {headers: {Zp_token: token, 'Content-Type': 'application/x-www-form-urlencoded'}},
                );
                if (updateResponse.data?.code !== 0) {
                    throw new Error(updateResponse.data?.message || '关闭BOSS平台默认招呼语失败');
                }
            }

            const verifyResponse = await axiosOriginal.get(getUrl, {
                headers: {Zp_token: token},
            });
            if (verifyResponse.data?.code !== 0 || Number(verifyResponse.data?.zpData?.greeting?.status) === 1) {
                throw new Error('BOSS平台默认招呼语未确认关闭，已取消本次投递');
            }
            this.bossAutomaticGreetingDisabled = true;
            this.logRecorder.info('已确认关闭BOSS平台默认招呼语，投递将使用岗位化AI招呼语');
        })();
        try {
            await this.bossGreetingDisablePromise;
        } finally {
            this.bossGreetingDisablePromise = null;
        }
    }

    /**
     * 投递后发送自定义图片
     */
    async pushAfterSendImage(jobDetail: BossJobDetail) {
        if (!userStore.user.preference.cIE || this._pushMock) {
            return;
        }
        let bossData = await this.requestBossDataByCache(jobDetail);
        let customerImageSet = userStore.user.preference.cI;
        if (!customerImageSet) {
            return;
        }
        const trace: SendTraceContext = {
            action: 'POST_DELIVERY_IMAGE',
            channel: 'post-delivery',
            bossId: bossData.data.bossId,
            jobKey: this.getJobKey(jobDetail),
            jobTitle: jobDetail.jobName,
            content: 'image',
        };
        SendStateTracker.record(trace, 'GENERATED', 'post delivery image prepared');
        const imageParts = String(customerImageSet).split("===", 2);
        if (!imageParts[0] || !imageParts[1]) {
            SendStateTracker.record(trace, 'FAILED', 'image resume requires origin and tiny image URLs');
            logger.warn(`工作【${this.getJobKey(jobDetail)}】图片简历配置不完整`);
            return;
        }
        let message = new Message({
            form_uid: Tools.window._PAGE.uid.toString(),
            to_uid: bossData.data.bossId.toString(),
            to_name: jobDetail.encryptBossId,
            content: "",
            image: {
                originImage: imageParts[0],
                tinyImage: imageParts[1],
            },
            trace,
            recipient: {
                uid: bossData.data.bossId,
                encryptUid: bossData.data.encryptBossId || jobDetail.encryptBossId,
                friendSource: bossData.data.friendSource || 0,
                securityId: jobDetail.securityId,
                encryptJobId: jobDetail.encryptJobId,
                jobTitle: this.getJobKey(jobDetail),
            },
        });
        const sent = message.send();
        if (!sent) {
            SendStateTracker.record(trace, 'FAILED', 'post-delivery image sender unavailable');
            logger.warn(`工作【${this.getJobKey(jobDetail)}】图片简历发送失败`);
        }
    }

    pushPreHandler(jobDetail: JobDetail): JobDetail {
        return jobDetail;
    }

    async obtainBossJobDetailExt(jobDetail: BossJobDetail, message = '', retries = 3): Promise<any> {

        if (retries === 0) {
            logger.warn(`获取工作详情扩展信息异常,用于活跃度过滤以及工作内容过滤; 原因：${message}`)
            throw new NotMatchException(this.getJobKey(jobDetail), message, "获取工作详情扩展信息异常")
        }
        let params = `lid=${jobDetail.lid}&securityId=${jobDetail.securityId}&sessionId=`
        try {
            let resp = await axiosOriginal.get("https://www.zhipin.com/wapi/zpgeek/job/card.json?" + params, {timeout: 5000})
            return resp.data.zpData.jobCard
        } catch (error: any) {
            logger.debug("获取详情页异常正在重试:", error)
            return this.obtainBossJobDetailExt(jobDetail, error.message, retries - 1)
        }
    }

    bossIsActive(activeText: string) {
        return true;
    }

    isCommunication(jobCardJson: any) {
        return jobCardJson?.friendStatus === 1;
    }


}

const platformList: any[] = [BossPlatform]


class PlatformFactory {
    public static getInstance(url: string): Platform {
        for (const PlatformClass of platformList) {
            const platformInstance = new PlatformClass(url) as Platform;
            if (platformInstance.urlList.some(platformUrl => url.includes(platformUrl))) {
                // 计数器赋值
                pushResultCounter = pushResultCount();
                userStore = UserStore();
                userStore.platformType = platformInstance.getPlatformType();
                userRemoteLoad()
                return platformInstance;
            }
        }
        throw new PlatformError(PlatformTypeEnum.UnKnow, "错误的平台");
    }
}


export default PlatformFactory
