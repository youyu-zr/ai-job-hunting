package com.maple.ai.job.hunting.service.biz;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.config.AppBizConfig;
import com.maple.ai.job.hunting.consts.AIPromptStrConstant;
import com.maple.ai.job.hunting.consts.RePatternConstant;
import com.maple.ai.job.hunting.emums.AiSeatStatusEnum;
import com.maple.ai.job.hunting.emums.BizCodeEnum;
import com.maple.ai.job.hunting.emums.ProductTypeEnum;
import com.maple.ai.job.hunting.frame.cache.ProductNotAuthorizedCache;
import com.maple.ai.job.hunting.frame.cache.SysSessionCache;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.mapper.UserTrialMapper;
import com.maple.ai.job.hunting.model.AiFileResolveResult;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.bo.UserResumeDO;
import com.maple.ai.job.hunting.model.entity.PreferenceEntity;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.ai.AIServiceFacade;
import com.maple.ai.job.hunting.utils.FileTypeDetector;
import com.maple.ai.job.hunting.utils.LogValueSanitizer;
import com.maple.ai.job.hunting.utils.PreferenceDefaults;
import com.maple.ai.job.hunting.utils.ResumeTextExtractor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StopWatch;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/**
 * @author maple
 * Created Date: 2024/5/9 16:24
 * Description:
 */

@Slf4j
@Service
public class UserService {
    private static final SecureRandom SESSION_TOKEN_RANDOM = new SecureRandom();

    @Resource
    private AIServiceFacade aiServiceFacade;
    @Resource
    private UserInfoMapper userInfoMapper;
    @Resource
    private UserResumeMapper userResumeMapper;
    @Resource
    private SysSessionCache sysSessionCache;

    @Resource
    private ProductService productService;

    @Resource
    private UserTrialMapper userTrialMapper;

    @Resource
    private AppBizConfig appBizConfig;

    @Resource
    private UserAIConfigService userAIConfigService;

    @Resource
    private ProductNotAuthorizedCache productNotAuthorizedCache;

    @Resource
    private BetaAccessService betaAccessService;

    @Resource
    private BossIdentityVerifier bossIdentityVerifier;
    @Resource
    private TransactionTemplate transactionTemplate;

    private static final ThreadPoolExecutor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(2, 2,
            60L, TimeUnit.MINUTES, new java.util.concurrent.LinkedBlockingQueue<>(500));

    public UserInfoVO importResume(InputStream resumeFileStream, String uniqueId, String resumeId, String bossToken) throws Exception {
        String normalizedUniqueId = StringUtils.trimToEmpty(uniqueId);
        bossIdentityVerifier.requireOwnership(normalizedUniqueId, bossToken);
        byte[] bytes = resumeFileStream.readAllBytes();
        InputStream inputStream1 = new ByteArrayInputStream(bytes);
        InputStream inputStream2 = new ByteArrayInputStream(bytes);

        StopWatch watch = new StopWatch("导入简历");
        // 1. 本地优先解析简历；失败后再走原 AI 文件解析流程
        watch.start("本地解析简历");
        String summarize = ResumeTextExtractor.extract(bytes);
        watch.stop();

        if (!ResumeTextExtractor.hasUsableText(summarize)) {
            watch.start("AI识别简历");
            AiFileResolveResult aiFileResolveResult = aiServiceFacade.readFile(inputStream1, AIPromptStrConstant.AI_SEAT_SYSTEM_PROMPT);
            if (aiFileResolveResult == null || StringUtils.isBlank(aiFileResolveResult.getOriginalFileContent())) {
                throw new ApplicationException("简历解析失败，请换 PDF/DOCX/TXT 格式后重试");
            }
            summarize = aiFileResolveResult.getOriginalFileContent();
            watch.stop();
        }

        // 2. 正则匹配邮箱手机
        watch.start("正则匹配邮箱手机");
        UserInfoVO result = parsePhoneAndEmail(summarize);
        watch.stop();

        // 3. 保存用户信息
        watch.start("保存用户信息");
        String ossFileName = "简历-" + normalizedUniqueId + "-" + DateUtil.formatDate(new Date()) + "." + FileTypeDetector.detectFileType(bytes);
        String resumeSummary = summarize;
        transactionTemplate.execute(status -> {
            Long userId = saveUserInfo(normalizedUniqueId, resumeId, result, resumeSummary, ossFileName);
            betaAccessService.ensureAutoGrant(userId);
            return userId;
        });
        watch.stop();
        return new UserInfoVO(result.getPhone(), result.getEmail());
    }

    private Long saveUserInfo(String uniqueId, String resumeId, UserInfoVO result, String summarize, String ossFileName) {
        UserInfoDO dbUserInfo = userInfoMapper.selectByUniqueId(uniqueId);
        if (dbUserInfo == null) {
            UserInfoDO userInfoDO = new UserInfoDO(result.getPhone(), result.getEmail(), uniqueId);
            if (userInfoMapper.insert(userInfoDO) <= 0) {
                throw new ApplicationException("用户信息保存失败，请重试");
            }
            if (userResumeMapper.saveLatestResume(new UserResumeDO(
                    userInfoDO.getId(), summarize, ossFileName, resumeId)) <= 0) {
                throw new ApplicationException("简历保存失败，请重试");
            }
            return userInfoDO.getId();
        } else {
            // A duplicate-key upsert may return 0 when the stored values are unchanged.
            userResumeMapper.saveLatestResume(new UserResumeDO(
                    dbUserInfo.getId(), summarize, ossFileName, resumeId));
            LambdaUpdateWrapper<UserInfoDO> updateWrapper = new LambdaUpdateWrapper<UserInfoDO>()
                    .eq(UserInfoDO::getId, dbUserInfo.getId())
                    .set(UserInfoDO::getUniqueId, uniqueId);
            if (StringUtils.isNotBlank(result.getEmail())) {
                updateWrapper.set(UserInfoDO::getEmail, result.getEmail());
            }
            if (StringUtils.isNotBlank(result.getPhone())) {
                updateWrapper.set(UserInfoDO::getPhone, result.getPhone());
            }
            userInfoMapper.update(new UserInfoDO(), updateWrapper);
            return dbUserInfo.getId();
        }
    }

    public String loginSilently(String uniqueId, String bossToken) {
        String normalizedUniqueId = StringUtils.trimToEmpty(uniqueId);
        bossIdentityVerifier.requireOwnership(normalizedUniqueId, bossToken);
        UserInfoDO userInfoDO;
        try {
            userInfoDO = transactionTemplate.execute(status -> {
                UserInfoDO account = userInfoMapper.selectByUniqueId(normalizedUniqueId);
                if (account == null) {
                    account = new UserInfoDO(null, null, normalizedUniqueId);
                    if (userInfoMapper.insert(account) <= 0) {
                        throw new ApplicationException("账号创建失败，请重试");
                    }
                }
                betaAccessService.ensureAutoGrant(account.getId());
                return account;
            });
        } catch (DuplicateKeyException duplicateKeyException) {
            // A concurrent first login may win the unique UID insert while this transaction waits.
            // Re-read after rollback, then continue using the winning account.
            UserInfoDO concurrentAccount = userInfoMapper.selectByUniqueId(normalizedUniqueId);
            if (concurrentAccount == null) {
                throw duplicateKeyException;
            }
            userInfoDO = transactionTemplate.execute(status -> {
                betaAccessService.ensureAutoGrant(concurrentAccount.getId());
                return concurrentAccount;
            });
        }
        if (userInfoDO == null) {
            throw new ApplicationException("账号初始化失败，请重试");
        }

        byte[] tokenBytes = new byte[32];
        SESSION_TOKEN_RANDOM.nextBytes(tokenBytes);
        String loginKey = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        sysSessionCache.set(loginKey, BeanUtil.copyProperties(userInfoDO, UserInfoVO.class));
        return loginKey;
    }

    public UserInfoVO getUserInfo() {
        Long userId = HeaderContext.getHeader().getUserId();
        if (userId == null) {
            return null;
        }
        UserInfoDO userInfoDO = userInfoMapper.selectById(userId);
        UserResumeDO userResumeDO = userResumeMapper.selectOne(new LambdaQueryWrapper<UserResumeDO>().eq(UserResumeDO::getUserId, userInfoDO.getId()));
        UserInfoVO userInfoVO = BeanUtil.copyProperties(userInfoDO, UserInfoVO.class);
        userInfoVO.setPreference(resolvePreference(userInfoDO.getPreference()));
        Optional.ofNullable(userResumeDO).ifPresent(resumeDO -> userInfoVO.setResumeId(resumeDO.getResumeId()));

        //购买过ai坐席，查询是否过期
        if (Objects.nonNull(userInfoDO.getAiSeatStatus())) {
            boolean openAiSeat = AiSeatStatusEnum.OPEN.getCode().equals(userInfoDO.getAiSeatStatus());
            if (openAiSeat) {
                // 开启了ai坐席，但是不存在有效期内的AI坐席产品
                Set<Integer> userProductSet = productService.queryUserValidAllProductType(userId);
                boolean existAiSeat = userProductSet.contains(ProductTypeEnum.AI_SEAT.getCode());
                boolean openCustomApi = userAIConfigService.openCustomApi(userProductSet);
                boolean canTrial = userTrialMapper.canTrial(userId, ProductTypeEnum.AI_SEAT.getCode(), appBizConfig);
                if (!existAiSeat && !canTrial && !openCustomApi) {
                    openAiSeat = false;
                }
            }
            userInfoVO.setAiSeatStatus(openAiSeat);
        }
        return userInfoVO;
    }

    public void savePreference(UserInfoVO userInfoVO) {
        Long userId = HeaderContext.getHeader().getUserId();
        LambdaUpdateWrapper<UserInfoDO> condition = new LambdaUpdateWrapper<>();
        condition.eq(UserInfoDO::getId, userId);
        if (Objects.nonNull(userInfoVO.getPreference())) {
            condition.set(UserInfoDO::getPreference, JSONUtil.toJsonStr(resolvePreference(userInfoVO.getPreference())));
        }
        if (userInfoVO.getEmail() != null) {
            condition.set(UserInfoDO::getEmail, StringUtils.trimToEmpty(userInfoVO.getEmail()));
        }
        if (userInfoVO.getPhone() != null) {
            condition.set(UserInfoDO::getPhone, StringUtils.trimToEmpty(userInfoVO.getPhone()));
        }

        // 如果尝试开启AI坐席，则判断是否有在有效期内的AI坐席产品
        if (Objects.equals(userInfoVO.getAiSeatStatus(), AiSeatStatusEnum.OPEN.getBool())) {
            UserResumeDO userResumeDO = userResumeMapper.selectByUserId(userId);
            if (userResumeDO == null || StringUtils.isBlank(userResumeDO.getResumeContent())) {
                throw new ApplicationException("请先导入简历");
            }
            if (betaAccessService.requiresActivation(userId)) {
                throw new ApplicationException("请先获得内测权限后开启AI坐席", BizCodeEnum.PRODUCT_NOT_AUTHORIZED.getCode());
            }
            Set<Integer> productTypeSet = productService.queryUserValidAllProductType(userId);
            Integer productType = ProductTypeEnum.AI_SEAT.getCode();
            String userIdAndProductTypeKey = userId + "-" + productType;
            if (!(productTypeSet.contains(productType) || userAIConfigService.openCustomApi(productTypeSet))) {
                // 检查用户是否可以试用
                if (!userTrialMapper.canTrial(userId, productType, appBizConfig)) {
                    if (productNotAuthorizedCache.contains(userIdAndProductTypeKey)) {
                        productNotAuthorizedCache.set(userIdAndProductTypeKey, true);
                        throw new ApplicationException("AI坐席试用已结束；当前版本暂不提供付费开通");
                    } else {
                        productNotAuthorizedCache.set(userIdAndProductTypeKey, true);
                        throw new ApplicationException("AI坐席试用已结束；当前版本暂不提供付费开通", BizCodeEnum.PRODUCT_NOT_AUTHORIZED.getCode());
                    }
                }
                HeaderContext.getHeader().setRespMsg("AI坐席试用已开启");
            }
        }
        if (Objects.nonNull(userInfoVO.getAiSeatStatus())) {
            condition.set(UserInfoDO::getAiSeatStatus, userInfoVO.getAiSeatStatus());
        }
        // 必须要填入实体对象，mybatisPlus才能自动填充（set的字段还是按照condition中set的字段来更新，不用担心实体对象为空更新进去）
        userInfoMapper.update(new UserInfoDO(), condition);
    }

    private PreferenceEntity resolvePreference(Object rawPreference) {
        if (rawPreference == null) {
            return PreferenceDefaults.applyDefaults(new PreferenceEntity());
        }
        try {
            String preferenceJson = rawPreference instanceof CharSequence
                    ? rawPreference.toString()
                    : JSONUtil.toJsonStr(rawPreference);
            if (StringUtils.isBlank(preferenceJson)) {
                return PreferenceDefaults.applyDefaults(new PreferenceEntity());
            }
            PreferenceEntity preferenceEntity = JSONUtil.toBean(preferenceJson, PreferenceEntity.class);
            return PreferenceDefaults.applyDefaults(preferenceEntity);
        } catch (Exception e) {
            log.warn("偏好解析失败，回退到默认风险包: {}",
                    LogValueSanitizer.sanitize(e.getMessage(), 200));
            return PreferenceDefaults.applyDefaults(new PreferenceEntity());
        }
    }


    private static @NotNull UserInfoVO parsePhoneAndEmail(String summarize) {
        // 正则匹配； 数据入库，【手机号，邮箱，简历文本，标签，意向】
        Matcher phoneMatcher = RePatternConstant.PHONE_PATTERN.matcher(summarize);
        Matcher emailMatcher = RePatternConstant.EMAIL_PATTERN.matcher(summarize);

        String phone = "";
        if (phoneMatcher.find()) {
            phone = phoneMatcher.group(1);
        }

        String email = "";
        if (emailMatcher.find()) {
            email = emailMatcher.group(1);
        }

        if (StringUtils.isBlank(phone) || StringUtils.isBlank(email)) {
            log.warn(buildResumeParseWarning(phone, email, summarize));
        }

        return new UserInfoVO(phone, email);
    }

    static String buildResumeParseWarning(String phone, String email, String resume) {
        return "简历正则匹配失败 phonePresent:" + StringUtils.isNotBlank(phone)
                + " emailPresent:" + StringUtils.isNotBlank(email)
                + " resumeLength:" + StringUtils.length(resume);
    }
}
