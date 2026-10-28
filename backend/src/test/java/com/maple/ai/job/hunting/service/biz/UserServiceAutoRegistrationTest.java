package com.maple.ai.job.hunting.service.biz;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.maple.ai.job.hunting.config.BetaAccessConfig;
import com.maple.ai.job.hunting.frame.cache.SysSessionCache;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserProductMapper;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.bo.UserProductDO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceAutoRegistrationTest {

    @BeforeAll
    static void initializeMyBatisEntityMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "auto-registration-test"),
                UserProductDO.class);
    }

    @Test
    void firstVerifiedLoginCreatesEmptyProfileAndAutoGrantsWithoutResumeImport() {
        Fixture fixture = new Fixture(false);

        String token = fixture.userService.loginSilently("boss-user", "boss-token");

        UserInfoDO user = fixture.users.get("boss-user");
        assertNotNull(user);
        assertNull(user.getPhone());
        assertNull(user.getEmail());
        assertNotNull(user.getInviteCode());
        assertEquals(1, fixture.userInsertCount.get());
        assertEquals(1, fixture.betaProducts.size());
        assertEquals(user.getId(), fixture.betaProducts.get(0).getUserId());
        assertEquals(-2L, fixture.betaProducts.get(0).getOrderId());
        assertNotNull(fixture.sessions.get(token));
    }

    @Test
    void existingAccountLoginDoesNotDuplicateAccountOrBetaGrant() {
        Fixture fixture = new Fixture(false);

        String firstToken = fixture.userService.loginSilently("boss-user", "boss-token");
        String secondToken = fixture.userService.loginSilently("boss-user", "boss-token");

        assertNotNull(fixture.sessions.get(firstToken));
        assertNotNull(fixture.sessions.get(secondToken));
        assertEquals(1, fixture.userInsertCount.get());
        assertEquals(1, fixture.betaProducts.size());
    }

    @Test
    void firstLoginUsesNormalizedVerifiedUidForAccountAndGrant() {
        Fixture fixture = new Fixture(false);

        String token = fixture.userService.loginSilently("  boss-user\t", "boss-token");

        UserInfoDO user = fixture.users.get("boss-user");
        assertNotNull(user);
        assertEquals("boss-user", user.getUniqueId());
        assertEquals(1, fixture.userInsertCount.get());
        assertEquals(1, fixture.betaProducts.size());
        assertEquals(user.getId(), fixture.betaProducts.get(0).getUserId());
        assertNotNull(fixture.sessions.get(token));
    }

    @Test
    void concurrentFirstLoginUsesTheAccountCreatedByTheWinningRequest() {
        Fixture fixture = new Fixture(true);

        String token = fixture.userService.loginSilently("boss-user", "boss-token");

        assertNotNull(fixture.users.get("boss-user"));
        assertEquals(0, fixture.userInsertCount.get());
        assertEquals(1, fixture.betaProducts.size());
        assertNotNull(fixture.sessions.get(token));
    }

    @Test
    void failedBossIdentityVerificationDoesNotCreateAnAccountOrGrantAccess() {
        Fixture fixture = new Fixture(false);
        ReflectionTestUtils.setField(fixture.userService, "bossIdentityVerifier", new BossIdentityVerifier() {
            @Override
            public boolean authorizeLoginOrThrow(String claimedUid, String bossToken) {
                throw new IllegalStateException("identity verification failed");
            }
        });

        assertThrows(IllegalStateException.class,
                () -> fixture.userService.loginSilently("boss-user", "invalid-token"));

        assertTrue(fixture.users.isEmpty());
        assertEquals(0, fixture.userInsertCount.get());
        assertTrue(fixture.betaProducts.isEmpty());
    }

    @Test
    void aRefusedLoginByTheVerifierCreatesNothing() {
        Fixture fixture = new Fixture(false);
        ReflectionTestUtils.setField(fixture.userService, "bossIdentityVerifier", new BossIdentityVerifier() {
            @Override
            public boolean authorizeLoginOrThrow(String claimedUid, String bossToken) {
                // The real verifier throws on DENY; this stubs that contract directly.
                throw new com.maple.ai.job.hunting.frame.exp.ApplicationException(
                        com.maple.ai.job.hunting.emums.BizCodeEnum.IDENTITY_VERIFY_FAILED);
            }
        });

        assertThrows(com.maple.ai.job.hunting.frame.exp.ApplicationException.class,
                () -> fixture.userService.loginSilently("boss-user", "conflicting-token"));

        assertTrue(fixture.users.isEmpty());
        assertEquals(0, fixture.userInsertCount.get());
        assertTrue(fixture.betaProducts.isEmpty());
    }

    @Test
    void anUnverifiedLoginResumesAnExistingAccountAndRecordsItAsUnverified() {
        Fixture fixture = new Fixture(false);
        // An account that already exists (a real tester) keeps working while the provider cannot
        // decide ownership; the session just must not claim ownership was proved.
        UserInfoDO existing = new UserInfoDO(null, null, "boss-user");
        existing.setId(7L);
        fixture.users.put("boss-user", existing);
        ReflectionTestUtils.setField(fixture.userService, "bossIdentityVerifier", new BossIdentityVerifier() {
            @Override
            public boolean authorizeLoginOrThrow(String claimedUid, String bossToken) {
                return false;
            }
        });

        String token = fixture.userService.loginSilently("boss-user", "boss-token");

        assertNotNull(fixture.sessions.get(token));
        assertEquals(Boolean.FALSE, fixture.sessions.get(token).getOwnershipVerified());
        assertEquals(0, fixture.userInsertCount.get(), "an existing account must not be re-created");
    }

    @Test
    void anUnverifiedLoginCannotMintANewAccount() {
        Fixture fixture = new Fixture(false);
        // Seed one unrelated account so the fixture is a populated deployment like production.
        UserInfoDO seed = new UserInfoDO(null, null, "existing-tester");
        seed.setId(1L);
        fixture.users.put("existing-tester", seed);
        ReflectionTestUtils.setField(fixture.userService, "bossIdentityVerifier", new BossIdentityVerifier() {
            @Override
            public boolean authorizeLoginOrThrow(String claimedUid, String bossToken) {
                return false;
            }
        });

        assertThrows(com.maple.ai.job.hunting.frame.exp.ApplicationException.class,
                () -> fixture.userService.loginSilently("never-registered-uid", "boss-token"));

        assertEquals(0, fixture.userInsertCount.get());
        assertNull(fixture.users.get("never-registered-uid"));
        assertTrue(fixture.betaProducts.isEmpty(), "no beta grant may be issued for a refused login");
    }

    private static final class Fixture {
        private final Map<String, UserInfoDO> users = new HashMap<>();
        private final List<UserProductDO> betaProducts = new ArrayList<>();
        private final AtomicInteger userInsertCount = new AtomicInteger();
        private final SysSessionCache sessions = new SysSessionCache();
        private final UserService userService = new UserService();

        private Fixture(boolean simulateConcurrentWinner) {
            UserInfoMapper userInfoMapper = (UserInfoMapper) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{UserInfoMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "selectByUniqueId" -> users.get(args[0]);
                        case "insert" -> {
                            if (simulateConcurrentWinner) {
                                UserInfoDO winner = new UserInfoDO(null, null, "boss-user");
                                winner.setId(99L);
                                users.put("boss-user", winner);
                                throw new DuplicateKeyException("uk_user_info_unique_id");
                            }
                            UserInfoDO user = (UserInfoDO) args[0];
                            user.setId(41L);
                            users.put(user.getUniqueId(), user);
                            userInsertCount.incrementAndGet();
                            yield 1;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });

            UserProductMapper userProductMapper = (UserProductMapper) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{UserProductMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "selectList" -> {
                            LambdaQueryWrapper<?> condition = (LambdaQueryWrapper<?>) args[0];
                            condition.getSqlSegment();
                            boolean autoGrantOnly = condition.getParamNameValuePairs().values().stream()
                                    .anyMatch(value -> "-2".equals(String.valueOf(value)));
                            yield betaProducts.stream()
                                    .filter(product -> !autoGrantOnly
                                            || Long.valueOf(-2L).equals(product.getOrderId()))
                                    .toList();
                        }
                        case "insert" -> {
                            betaProducts.add((UserProductDO) args[0]);
                            yield 1;
                        }
                        case "updateById" -> 1;
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

            ReflectionTestUtils.setField(userService, "userInfoMapper", userInfoMapper);
            ReflectionTestUtils.setField(userService, "bossIdentityVerifier", new BossIdentityVerifier() {
                @Override
                public boolean authorizeLoginOrThrow(String claimedUid, String bossToken) {
                    assertEquals("boss-user", claimedUid);
                    assertEquals("boss-token", bossToken);
                    return true;
                }
            });
            ReflectionTestUtils.setField(userService, "betaAccessService", betaAccessService);
            ReflectionTestUtils.setField(userService, "sysSessionCache", sessions);
            ReflectionTestUtils.setField(userService, "transactionTemplate",
                    new TransactionTemplate(new NoopTransactionManager()));
        }
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
