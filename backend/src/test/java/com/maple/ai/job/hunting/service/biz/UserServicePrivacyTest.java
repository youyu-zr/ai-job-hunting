package com.maple.ai.job.hunting.service.biz;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.maple.ai.job.hunting.config.BetaAccessConfig;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.mapper.UserProductMapper;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.bo.UserProductDO;
import com.maple.ai.job.hunting.model.bo.UserResumeDO;
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
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void successfulResumeImportGrantsBetaOnlyAfterUserAndResumeAreSaved() throws Exception {
        AtomicInteger persistenceStage = new AtomicInteger();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        List<UserProductDO> grantedProducts = new ArrayList<>();
        UserInfoMapper userInfoMapper = (UserInfoMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserInfoMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectByUniqueId" -> null;
                    case "insert" -> {
                        ((UserInfoDO) args[0]).setId(42L);
                        persistenceStage.set(1);
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
                        assertEquals(1, persistenceStage.get());
                        persistenceStage.set(2);
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
        byte[] resume = "A".repeat(120).getBytes(StandardCharsets.UTF_8);

        ReflectionTestUtils.setField(userService, "bossIdentityVerifier", verifiedBossIdentityVerifier());
        ReflectionTestUtils.setField(userService, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(userService, "userResumeMapper", userResumeMapper);
        ReflectionTestUtils.setField(userService, "betaAccessService", betaAccessService);
        ReflectionTestUtils.setField(userService, "transactionTemplate", new TransactionTemplate(transactionManager));

        userService.importResume(new ByteArrayInputStream(resume), "boss-user", "resume-1", "token");

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
        UserInfoMapper userInfoMapper = (UserInfoMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserInfoMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectByUniqueId" -> null;
                    case "insert" -> {
                        ((UserInfoDO) args[0]).setId(42L);
                        yield 1;
                    }
                    case "toString" -> "RecordingUserInfoMapper";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UserResumeMapper userResumeMapper = (UserResumeMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserResumeMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "saveLatestResume" -> 0;
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
        ReflectionTestUtils.setField(userService, "bossIdentityVerifier", verifiedBossIdentityVerifier());
        ReflectionTestUtils.setField(userService, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(userService, "userResumeMapper", userResumeMapper);
        ReflectionTestUtils.setField(userService, "transactionTemplate", new TransactionTemplate(transactionManager));
        ReflectionTestUtils.setField(userService, "betaAccessService", new BetaAccessService(
                userProductMapper,
                productService,
                new BetaAccessConfig("2026-12-30T23:59:59.999+08:00")));
        byte[] resume = "A".repeat(120).getBytes(StandardCharsets.UTF_8);

        assertThrows(ApplicationException.class, () -> userService.importResume(
                new ByteArrayInputStream(resume), "boss-user", "resume-1", "token"));

        assertEquals(0, entitlementQueries.get());
        assertEquals(0, transactionManager.commits.get());
        assertEquals(1, transactionManager.rollbacks.get());
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
                    case "selectByUniqueId" -> existingUser;
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
        ReflectionTestUtils.setField(userService, "bossIdentityVerifier", verifiedBossIdentityVerifier());
        ReflectionTestUtils.setField(userService, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(userService, "userResumeMapper", userResumeMapper);
        ReflectionTestUtils.setField(userService, "transactionTemplate",
                new TransactionTemplate(new RecordingTransactionManager()));
        ReflectionTestUtils.setField(userService, "betaAccessService", new BetaAccessService(
                userProductMapper,
                productService,
                new BetaAccessConfig("2026-12-30T23:59:59.999+08:00")));

        byte[] resume = "replacement resume ".repeat(8).getBytes(StandardCharsets.UTF_8);
        userService.importResume(new ByteArrayInputStream(resume), "boss-user", "resume-2", "token");

        assertEquals(1, resumeUpserts.get());
    }

    private static BossIdentityVerifier verifiedBossIdentityVerifier() {
        return new BossIdentityVerifier() {
            @Override
            JSONObject fetchUserInfo(String bossToken) {
                return JSONUtil.parseObj("{\"code\":0,\"zpData\":{\"uid\":\"boss-user\"}}");
            }
        };
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
