package com.maple.ai.job.hunting.service.biz;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.config.BetaAccessConfig;
import com.maple.ai.job.hunting.consts.RePatternConstant;
import com.maple.ai.job.hunting.emums.BizCodeEnum;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.mapper.UserProductMapper;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.bo.UserProductDO;
import com.maple.ai.job.hunting.model.bo.UserResumeDO;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class UserServicePrivacyTest {

    @Test
    void resumeParseWarningDoesNotLogContactValuesOrResumeContent() {
        String warning = UserService.buildResumeParseWarning(
                "13800138000", "candidate@example.com", "candidate private resume content"
        );

        assertTrue(warning.contains("phonePresent:true"));
        assertTrue(warning.contains("emailPresent:true"));
        assertTrue(warning.contains("resumeLength:32"));
        assertFalse(warning.contains("13800138000"));
        assertFalse(warning.contains("candidate@example.com"));
        assertFalse(warning.contains("candidate private resume content"));
    }

    @Test
    void hostileResumeContactTextIsBounded() {
        String phoneCandidate = " ".repeat(50_000) + "x";
        String emailCandidate = "邮箱 " + "mailaa".repeat(50_000);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            RePatternConstant.PHONE_PATTERN.matcher(phoneCandidate).find();
            RePatternConstant.EMAIL_PATTERN.matcher(emailCandidate).find();
        });
    }
    @Test
    void phonePatternAcceptsNewerPrefixes() {
        assertTrue(RePatternConstant.PHONE_PATTERN.matcher("电话 19912345678").find());
        assertTrue(RePatternConstant.PHONE_PATTERN.matcher("电话 16612345678").find());
        assertFalse(RePatternConstant.PHONE_PATTERN.matcher("电话 12345678901").find());
    }

    @Test
    void wechatIsOnlyReadWhenLabelled() {
        assertEquals("zr_2026abc", UserService.parseWechat("微信：zr_2026abc  电话 13800138000"));
        assertEquals("zr-wx2026", UserService.parseWechat("WeChat ID: zr-wx2026"));
        assertEquals("abc12345", UserService.parseWechat("微信号 abc12345\n邮箱 a@b.cn"));
        // 「微信同手机」、纯数字、没有标签的英文串都不当成微信号
        assertEquals("zhang_san88", UserService.parseWechat("VX: zhang_san88"));
        assertEquals("zhang_san88", UserService.parseWechat("wx：zhang_san88"));
        assertEquals("zhang_san88", UserService.parseWechat("微 信：zhang_san88"));
        assertEquals("", UserService.parseWechat("GitHub: zr_2026abc"));
        assertEquals("", UserService.parseWechat(null));
    }

    @Test
    void wechatSameAsPhoneUsesThePhone() {
        assertEquals("13800138000", UserService.parseWechat("微信同手机 13800138000", "13800138000"));
        assertEquals("13800138000", UserService.parseWechat("电话 13800138000（微信同号）", "13800138000"));
        assertEquals("13800138000", UserService.parseWechat("手机/微信：13800138000", "13800138000"));
        assertEquals("13800138000", UserService.parseWechat("微信：13800138000", ""));
    }

    @Test
    void techWordsAndEmailsAreNotMistakenForWechat() {
        assertEquals("", UserService.parseWechat("负责微信 MiniProgram 开发"));
        assertEquals("", UserService.parseWechat("熟悉 WeChat Official Account"));
        assertEquals("", UserService.parseWechat("熟悉 vxe-table 组件"));
        assertEquals("", UserService.parseWechat("邮箱/微信：zhangsan88@163.com"));
        assertEquals("", UserService.parseWechat("GitHub devxlabs01"));
        // 技术词之后真正的微信号仍能找到
        assertEquals("real_wx01", UserService.parseWechat("微信 MiniProgram 开发\n微信：real_wx01"));
    }

    @Test
    void moreTechWordsAndLooseWritings() {
        assertEquals("", UserService.parseWechat("负责微信 Native 支付"));
        assertEquals("", UserService.parseWechat("微信Android客户端"));
        assertEquals("", UserService.parseWechat("微信 OAuth2 登录"));
        assertEquals("", UserService.parseWechat("微信 uni-app 开发"));
        assertEquals("zhangsan88", UserService.parseWechat("微信：zhangsan88."));
        assertEquals("abc_123", UserService.parseWechat("微 信 号：abc_123"));
        assertEquals("zhangsan88", UserService.parseWechat("vx zhangsan88"));
        assertEquals("13800138000", UserService.parseWechat("微信：同手机", "13800138000"));
        assertEquals("13800138000", UserService.parseWechat("微信（同手机）", "13800138000"));
        assertEquals("13800138000", UserService.parseWechat("微信号：同手机号", "13800138000"));
    }

    @Test
    void longWhitespaceAfterLabelDoesNotHang() {
        String hostile = "微信" + " ".repeat(50_000) + "x";
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> UserService.parseWechat(hostile));
    }

    @Test
    void wechatFillNeverOverwritesUserValueOrBrokenPreference() {
        UserService userService = new UserService();
        assertEquals(null, userService.fillWechatIfBlank("{\"wechatId\":\"mine_123\"}", "resume_456"));
        assertEquals(null, userService.fillWechatIfBlank("{not json", "resume_456"));
        assertEquals(null, userService.fillWechatIfBlank("{}", ""));
        // 本人清空过（存的是空串）就不再填回去
        assertEquals(null, userService.fillWechatIfBlank("{\"wechatId\":\"\"}", "resume_456"));

        String filled = userService.fillWechatIfBlank("{\"salaryFloorK\":15}", "resume_456");
        JSONObject json = JSONUtil.parseObj(filled);
        assertEquals("resume_456", json.getStr("wechatId"));
        assertEquals(15, json.getInt("salaryFloorK"));
        // 自动填微信号不能顺带打开「允许 AI 发联系方式」
        assertFalse(Boolean.TRUE.equals(json.getBool("contactShareE")));
    }

    @Test
    void successfulResumeImportGrantsBetaOnlyAfterUserAndResumeAreSaved() throws Exception {
        AtomicInteger persistenceStage = new AtomicInteger();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        List<UserProductDO> grantedProducts = new ArrayList<>();
        UserInfoDO authenticatedUser = new UserInfoDO();
        authenticatedUser.setId(42L);
        authenticatedUser.setUniqueId("boss-user");
        UserInfoMapper userInfoMapper = (UserInfoMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserInfoMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById", "selectByIdForUpdate" -> authenticatedUser;
                    case "update" -> {
                        assertEquals(1, persistenceStage.get());
                        persistenceStage.set(2);
                        yield 1;
                    }
                    case "toString" -> "RecordingUserInfoMapper";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UserResumeMapper userResumeMapper = (UserResumeMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserResumeMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "saveLatestResume" -> {
                        UserResumeDO saved = (UserResumeDO) args[0];
                        assertEquals(42L, saved.getUserId());
                        assertEquals("resume-1", saved.getResumeId());
                        persistenceStage.set(1);
                        yield 1;
                    }
                    case "toString" -> "RecordingUserResumeMapper";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UserProductMapper userProductMapper = (UserProductMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserProductMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectList" -> List.of();
                    case "insert" -> {
                        assertEquals(2, persistenceStage.get());
                        grantedProducts.add((UserProductDO) args[0]);
                        persistenceStage.set(3);
                        yield 1;
                    }
                    case "toString" -> "RecordingUserProductMapper";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ProductService productService = new ProductService() {
            @Override
            public boolean isPermanentUser(Long userId) {
                return false;
            }
        };
        BetaAccessService betaAccessService = new BetaAccessService(
                userProductMapper,
                productService,
                new BetaAccessConfig("2026-12-30T23:59:59.999+08:00"));
        UserService userService = new UserService();
        byte[] resume = ("candidate@example.com 13800138000 " + "A".repeat(120))
                .getBytes(StandardCharsets.UTF_8);

        ReflectionTestUtils.setField(userService, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(userService, "userResumeMapper", userResumeMapper);
        ReflectionTestUtils.setField(userService, "betaAccessService", betaAccessService);
        ReflectionTestUtils.setField(userService, "transactionTemplate", new TransactionTemplate(transactionManager));
        MapperBuilderAssistant mapperBuilderAssistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "user-info-contact-update-test");
        mapperBuilderAssistant.setCurrentNamespace(UserInfoMapper.class.getName());
        TableInfoHelper.initTableInfo(mapperBuilderAssistant, UserInfoDO.class);

        setAuthenticatedUser(42L);
        try {
            userService.importResume(new ByteArrayInputStream(resume), "resume-1");
        } finally {
            HeaderContext.clear();
        }

        assertEquals(3, persistenceStage.get());
        assertEquals(1, grantedProducts.size());
        assertEquals(42L, grantedProducts.get(0).getUserId());
        assertEquals(-2L, grantedProducts.get(0).getOrderId());
        assertEquals(1, transactionManager.commits.get());
        assertEquals(0, transactionManager.rollbacks.get());
    }

    @Test
    void failedResumeWriteDoesNotGrantBetaAccess() {
        AtomicInteger entitlementQueries = new AtomicInteger();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        UserInfoDO authenticatedUser = new UserInfoDO();
        authenticatedUser.setId(42L);
        authenticatedUser.setUniqueId("boss-user");
        UserInfoMapper userInfoMapper = (UserInfoMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserInfoMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById", "selectByIdForUpdate" -> authenticatedUser;
                    case "toString" -> "RecordingUserInfoMapper";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UserResumeMapper userResumeMapper = (UserResumeMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserResumeMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "saveLatestResume" -> throw new IllegalStateException("synthetic resume write failure");
                    case "toString" -> "FailingUserResumeMapper";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UserProductMapper userProductMapper = (UserProductMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserProductMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectList" -> {
                        entitlementQueries.incrementAndGet();
                        yield List.of();
                    }
                    case "insert" -> 1;
                    case "toString" -> "RecordingUserProductMapper";
                    default -> throw new UnsupportedOperationException(method.getName());
        });
        ProductService productService = new ProductService() {
            @Override
            public boolean isPermanentUser(Long userId) {
                return false;
            }
        };
        UserService userService = new UserService();
        ReflectionTestUtils.setField(userService, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(userService, "userResumeMapper", userResumeMapper);
        ReflectionTestUtils.setField(userService, "transactionTemplate", new TransactionTemplate(transactionManager));
        ReflectionTestUtils.setField(userService, "betaAccessService", new BetaAccessService(
                userProductMapper,
                productService,
                new BetaAccessConfig("2026-12-30T23:59:59.999+08:00")));
        byte[] resume = "A".repeat(120).getBytes(StandardCharsets.UTF_8);

        setAuthenticatedUser(42L);
        try {
            assertThrows(IllegalStateException.class, () -> userService.importResume(
                    new ByteArrayInputStream(resume), "resume-1"));
        } finally {
            HeaderContext.clear();
        }

        assertEquals(0, entitlementQueries.get());
        assertEquals(0, transactionManager.commits.get());
        assertEquals(1, transactionManager.rollbacks.get());
    }

    @Test
    void resumeImportRejectsMissingApplicationSessionBeforeReadingResume() {
        HeaderContext.clear();
        UserService userService = new UserService();
        try {
            ApplicationException failure = assertThrows(ApplicationException.class,
                    () -> userService.importResume(new ByteArrayInputStream(new byte[]{1, 2, 3}), "resume-1"));

            assertEquals(BizCodeEnum.NOT_LOGIN.getCode(), failure.getBizCode());
        } finally {
            HeaderContext.clear();
        }
    }

    @Test
    void replacingResumeUpdatesTheExistingUniqueRowWithoutLogicalDelete() throws Exception {
        MapperBuilderAssistant mapperBuilderAssistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "user-resume-replacement-test");
        mapperBuilderAssistant.setCurrentNamespace(UserInfoMapper.class.getName());
        TableInfoHelper.initTableInfo(mapperBuilderAssistant, UserInfoDO.class);
        AtomicInteger resumeUpserts = new AtomicInteger();
        UserInfoDO existingUser = new UserInfoDO();
        existingUser.setId(42L);
        existingUser.setUniqueId("boss-user");
        UserInfoMapper userInfoMapper = (UserInfoMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserInfoMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectById", "selectByIdForUpdate" -> existingUser;
                    case "update" -> 1;
                    case "toString" -> "ExistingUserInfoMapper";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UserResumeMapper userResumeMapper = (UserResumeMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserResumeMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "saveLatestResume" -> {
                        UserResumeDO replacement = (UserResumeDO) args[0];
                        assertEquals(42L, replacement.getUserId());
                        assertEquals("resume-2", replacement.getResumeId());
                        assertTrue(replacement.getResumeContent().contains("replacement resume"));
                        resumeUpserts.incrementAndGet();
                        yield 0;
                    }
                    case "delete" -> throw new AssertionError("resume replacement must not soft-delete the unique row");
                    case "toString" -> "RecordingUserResumeMapper";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UserProductMapper userProductMapper = (UserProductMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserProductMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectList" -> List.of();
                    case "insert" -> 1;
                    case "toString" -> "RecordingUserProductMapper";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ProductService productService = new ProductService() {
            @Override
            public boolean isPermanentUser(Long userId) {
                return false;
            }
        };
        UserService userService = new UserService();
        ReflectionTestUtils.setField(userService, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(userService, "userResumeMapper", userResumeMapper);
        ReflectionTestUtils.setField(userService, "transactionTemplate",
                new TransactionTemplate(new RecordingTransactionManager()));
        ReflectionTestUtils.setField(userService, "betaAccessService", new BetaAccessService(
                userProductMapper,
                productService,
                new BetaAccessConfig("2026-12-30T23:59:59.999+08:00")));

        byte[] resume = "replacement resume ".repeat(8).getBytes(StandardCharsets.UTF_8);
        setAuthenticatedUser(42L);
        try {
            userService.importResume(new ByteArrayInputStream(resume), "resume-2");
        } finally {
            HeaderContext.clear();
        }

        assertEquals(1, resumeUpserts.get());
    }

    private static void setAuthenticatedUser(Long userId) {
        UserInfoVO sessionUser = new UserInfoVO();
        sessionUser.setId(userId);

        Header header = new Header();
        header.setUserInfoVO(sessionUser);
        HeaderContext.setHeader(header);
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks.incrementAndGet();
        }
    }
}
