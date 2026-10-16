package com.maple.ai.job.hunting.service.biz;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maple.ai.job.hunting.config.BetaAccessConfig;
import com.maple.ai.job.hunting.emums.ProductEnum;
import com.maple.ai.job.hunting.mapper.UserProductMapper;
import com.maple.ai.job.hunting.model.bo.UserProductDO;
import com.maple.ai.job.hunting.model.vo.BetaAccessVO;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetaAccessServiceTest {

    @BeforeAll
    static void initializeMyBatisEntityMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "test"), UserProductDO.class);
    }

    private static final Date DEADLINE = Date.from(
            OffsetDateTime.parse("2026-12-30T23:59:59.999+08:00").toInstant());
    private static final Date OPENED_AT = Date.from(Instant.parse("2026-08-27T00:00:00Z"));

    @Test
    void automaticallyGrantsOnceUntilConfiguredChinaTime() {
        RecordingProductMapper recordingMapper = new RecordingProductMapper();
        BetaAccessService service = service(recordingMapper, false);

        service.ensureAutoGrant(9L, OPENED_AT);
        service.ensureAutoGrant(9L, OPENED_AT);
        BetaAccessVO first = service.getAccessStatus(9L, OPENED_AT);
        BetaAccessVO second = service.getAccessStatus(9L, OPENED_AT);

        assertTrue(first.isActivationEnabled());
        assertTrue(first.isAccessActive());
        assertEquals(DEADLINE, first.getPeriodOfValidityEndTime());
        assertEquals(DEADLINE, second.getPeriodOfValidityEndTime());
        assertEquals(1, recordingMapper.insertCount);
        assertEquals(-2L, recordingMapper.insertedProduct.getOrderId());
    }

    @Test
    void statusChecksDoNotGrantAccessAsASideEffect() {
        RecordingProductMapper recordingMapper = new RecordingProductMapper();
        BetaAccessService service = service(recordingMapper, false);

        BetaAccessVO status = service.getAccessStatus(9L, OPENED_AT);

        assertTrue(status.isActivationEnabled());
        assertFalse(status.isAccessActive());
        assertEquals(0, recordingMapper.insertCount);
    }

    @Test
    void extendsExistingShortAutoGrantButPreservesLongerExpiry() {
        UserProductDO existing = betaProduct(-2L, Date.from(Instant.parse("2026-09-03T00:00:00Z")));
        RecordingProductMapper recordingMapper = new RecordingProductMapper(existing);
        BetaAccessService service = service(recordingMapper, false);

        service.ensureAutoGrant(9L, OPENED_AT);
        service.getAccessStatus(9L, OPENED_AT);

        assertEquals(DEADLINE, existing.getPeriodOfValidityEndTime());
        assertEquals(1, recordingMapper.updateCount);

        Date longerExpiry = Date.from(Instant.parse("2027-01-01T00:00:00Z"));
        existing.setPeriodOfValidityEndTime(longerExpiry);
        recordingMapper.updateCount = 0;
        service.ensureAutoGrant(9L, Date.from(Instant.parse("2026-08-28T00:00:00Z")));

        assertEquals(longerExpiry, existing.getPeriodOfValidityEndTime());
        assertEquals(0, recordingMapper.updateCount);
    }

    @Test
    void doesNotModifySeparateHistoricalBetaOrder() {
        UserProductDO historical = betaProduct(1001L, Date.from(Instant.parse("2026-09-03T00:00:00Z")));
        RecordingProductMapper recordingMapper = new RecordingProductMapper(historical);
        BetaAccessService service = service(recordingMapper, false);

        service.ensureAutoGrant(9L, OPENED_AT);
        BetaAccessVO status = service.getAccessStatus(9L, OPENED_AT);

        assertEquals(Date.from(Instant.parse("2026-09-03T00:00:00Z")),
                historical.getPeriodOfValidityEndTime());
        assertEquals(0, recordingMapper.updateCount);
        assertEquals(1, recordingMapper.insertCount);
        assertEquals(DEADLINE, status.getPeriodOfValidityEndTime());
    }

    @Test
    void doesNotGrantAtOrAfterDeadline() {
        RecordingProductMapper recordingMapper = new RecordingProductMapper();
        BetaAccessService service = service(recordingMapper, false);

        BetaAccessVO status = service.getAccessStatus(9L, DEADLINE);

        assertFalse(status.isActivationEnabled());
        assertFalse(status.isAccessActive());
        assertNull(recordingMapper.insertedProduct);
    }

    @Test
    void permanentAccountDoesNotReceiveRedundantAutoGrant() {
        RecordingProductMapper recordingMapper = new RecordingProductMapper();
        BetaAccessService service = service(recordingMapper, true);

        service.ensureAutoGrant(9L, OPENED_AT);
        BetaAccessVO status = service.getAccessStatus(9L, OPENED_AT);

        assertTrue(status.isPermanent());
        assertTrue(status.isAccessActive());
        assertEquals(0, recordingMapper.insertCount);
    }

    @Test
    void duplicateInsertRaceIsResolvedByTheDatabaseUniqueKey() {
        List<UserProductDO> rows = new ArrayList<>();
        AtomicInteger autoGrantReads = new AtomicInteger();
        AtomicInteger successfulInserts = new AtomicInteger();
        UserProductMapper mapper = (UserProductMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserProductMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectList" -> {
                        LambdaQueryWrapper<?> condition = (LambdaQueryWrapper<?>) args[0];
                        boolean autoGrantOnly = isAutoGrantOnly(condition);
                        if (autoGrantOnly && autoGrantReads.incrementAndGet() <= 2) {
                            yield List.of();
                        }
                        yield rows.stream()
                                .filter(row -> !autoGrantOnly || Long.valueOf(-2L).equals(row.getOrderId()))
                                .toList();
                    }
                    case "insert" -> {
                        if (!rows.isEmpty()) {
                            throw new DuplicateKeyException("uk_beta_auto_grant_user");
                        }
                        rows.add((UserProductDO) args[0]);
                        successfulInserts.incrementAndGet();
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        BetaAccessService service = new BetaAccessService(
                mapper, new TestProductService(false),
                new BetaAccessConfig("2026-12-30T23:59:59.999+08:00"));

        service.ensureAutoGrant(9L, OPENED_AT);
        service.ensureAutoGrant(9L, OPENED_AT);

        assertEquals(1, rows.size());
        assertEquals(1, successfulInserts.get());
    }

    @Test
    void duplicateGrantRecoveryUsesCurrentReadInsteadOfTheOldTransactionSnapshot() {
        List<UserProductDO> committedByConcurrentRequest = new ArrayList<>();
        UserProductMapper mapper = (UserProductMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserProductMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectList" -> {
                        LambdaQueryWrapper<?> condition = (LambdaQueryWrapper<?>) args[0];
                        String sqlSegment = condition.getSqlSegment();
                        yield sqlSegment.endsWith("FOR UPDATE")
                                ? committedByConcurrentRequest
                                : List.of();
                    }
                    case "insert" -> {
                        committedByConcurrentRequest.add((UserProductDO) args[0]);
                        throw new DuplicateKeyException("uk_beta_auto_grant_user");
                    }
                    case "updateById" -> 1;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        BetaAccessService service = new BetaAccessService(
                mapper, new TestProductService(false),
                new BetaAccessConfig("2026-12-30T23:59:59.999+08:00"));

        service.ensureAutoGrant(9L, OPENED_AT);

        assertEquals(1, committedByConcurrentRequest.size());
        assertEquals(-2L, committedByConcurrentRequest.get(0).getOrderId());
    }

    @Test
    void legacySingleBetaUniqueKeyDoesNotBreakExistingHistoricalAccess() {
        UserProductDO historical = betaProduct(1001L, Date.from(Instant.parse("2026-09-03T00:00:00Z")));
        RecordingProductMapper recordingMapper = new RecordingProductMapper(historical);
        BetaAccessService service = new BetaAccessService(
                recordingMapper.mapper(product -> {
                    throw new DuplicateKeyException("uk_beta_user");
                }),
                new TestProductService(false),
                new BetaAccessConfig("2026-12-30T23:59:59.999+08:00"));

        service.ensureAutoGrant(9L, OPENED_AT);

        assertEquals(0, recordingMapper.insertCount);
        BetaAccessVO status = service.getAccessStatus(9L, OPENED_AT);
        assertTrue(status.isAccessActive());
        assertEquals(historical.getPeriodOfValidityEndTime(), status.getPeriodOfValidityEndTime());
    }

    @Test
    void legacySingleBetaUniqueKeyExtendsExpiredBetaInsteadOfSilentlySkippingGrant() {
        UserProductDO expired = betaProduct(1001L, Date.from(Instant.parse("2026-08-01T00:00:00Z")));
        RecordingProductMapper recordingMapper = new RecordingProductMapper(expired);
        BetaAccessService service = new BetaAccessService(
                recordingMapper.mapper(product -> {
                    throw new DuplicateKeyException("uk_beta_user");
                }),
                new TestProductService(false),
                new BetaAccessConfig("2026-12-30T23:59:59.999+08:00"));

        service.ensureAutoGrant(9L, OPENED_AT);

        assertEquals(DEADLINE, expired.getPeriodOfValidityEndTime());
        assertEquals(1, recordingMapper.updateCount);
        assertTrue(service.getAccessStatus(9L, OPENED_AT).isAccessActive());
    }

    private static BetaAccessService service(RecordingProductMapper mapper, boolean permanent) {
        return new BetaAccessService(
                mapper.mapper(), new TestProductService(permanent),
                new BetaAccessConfig("2026-12-30T23:59:59.999+08:00"));
    }

    private static boolean isAutoGrantOnly(LambdaQueryWrapper<?> condition) {
        condition.getSqlSegment();
        return condition.getParamNameValuePairs().values().stream()
                .anyMatch(value -> "-2".equals(String.valueOf(value)));
    }

    private static UserProductDO betaProduct(Long orderId, Date expiresAt) {
        UserProductDO product = new UserProductDO();
        product.setUserId(9L);
        product.setOrderId(orderId);
        product.setProductId(ProductEnum.BETA.getCode().longValue());
        product.setProductType(ProductEnum.BETA.getProductTypes().toString());
        product.setPeriodOfValidityEndTime(expiresAt);
        return product;
    }

    private static final class TestProductService extends ProductService {
        private final boolean permanent;

        private TestProductService(boolean permanent) {
            this.permanent = permanent;
        }

        @Override
        public boolean isPermanentUser(Long userId) {
            return permanent;
        }

        @Override
        public boolean hasAnyValidProduct(Long userId) {
            return false;
        }
    }

    @FunctionalInterface
    private interface InsertHandler {
        int insert(UserProductDO product);
    }

    private static final class RecordingProductMapper {
        private final List<UserProductDO> rows = new ArrayList<>();
        private UserProductDO insertedProduct;
        private int insertCount;
        private int updateCount;

        private RecordingProductMapper() {
        }

        private RecordingProductMapper(UserProductDO existing) {
            if (existing != null) {
                rows.add(existing);
            }
        }

        private UserProductMapper mapper() {
            return mapper(product -> {
                rows.add(product);
                insertedProduct = product;
                insertCount++;
                return 1;
            });
        }

        private UserProductMapper mapper(InsertHandler insertHandler) {
            return (UserProductMapper) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{UserProductMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "selectList" -> {
                            LambdaQueryWrapper<?> condition = (LambdaQueryWrapper<?>) args[0];
                            boolean autoGrantOnly = isAutoGrantOnly(condition);
                            yield rows.stream()
                                    .filter(row -> !autoGrantOnly || Long.valueOf(-2L).equals(row.getOrderId()))
                                    .toList();
                        }
                        case "insert" -> insertHandler.insert((UserProductDO) args[0]);
                        case "updateById" -> {
                            updateCount++;
                            yield 1;
                        }
                        case "toString" -> "RecordingProductMapper";
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
            );
        }
    }
}
