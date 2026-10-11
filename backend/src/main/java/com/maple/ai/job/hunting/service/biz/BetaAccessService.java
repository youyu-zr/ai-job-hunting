package com.maple.ai.job.hunting.service.biz;

/*
 * ==========================================================================
 * 内测权益幂等约束
 * --------------------------------------------------------------------------
 * 唯一约束只保护 BETA 自动授权记录，不限制其他权益记录：
 *   - JVM 内的 per-user 锁只是单机廉价过滤，无法跨实例、也无法解决
 *     “锁已释放但事务尚未提交”的窗口问题；
 *   - 新表结构可以由数据库唯一索引兜底，请执行：
 *
 *       ALTER TABLE ai_job.user_product
 *       ADD COLUMN beta_auto_grant_user_id bigint
 *           GENERATED ALWAYS AS (
 *               CASE WHEN product_id = 6 AND order_id = -2 THEN user_id ELSE NULL END
 *           ) STORED,
 *       ADD UNIQUE KEY uk_beta_auto_grant_user (beta_auto_grant_user_id);
 *
 * 说明：
 *   1. BETA 自动授权使用固定 order_id=-2，因此可以幂等；
 *   2. 其他产品和邀请兑换记录不受这条唯一约束影响；
 *   3. 现有历史实例可能只有 uk_beta_user (user_id, product_id)；
 *      新用户仍可自动领取，遇到已有历史 BETA 记录时保留原记录并继续使用，
 *      不在上线时强制改表。若要支持同一用户并存多种 BETA 来源，再按迁移文档处理。
 * ==========================================================================
 */

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maple.ai.job.hunting.config.BetaAccessConfig;
import com.maple.ai.job.hunting.emums.ProductEnum;
import com.maple.ai.job.hunting.mapper.UserProductMapper;
import com.maple.ai.job.hunting.model.bo.UserProductDO;
import com.maple.ai.job.hunting.model.vo.BetaAccessVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class BetaAccessService {

    private static final long AUTO_GRANT_ORDER_ID = -2L;

    private final UserProductMapper userProductMapper;
    private final ProductService productService;
    private final BetaAccessConfig betaAccessConfig;
    private final Map<Long, Object> activationLocks = new ConcurrentHashMap<>();

    public BetaAccessVO getAccessStatus(Long userId) {
        return getAccessStatus(userId, new Date());
    }

    BetaAccessVO getAccessStatus(Long userId, Date now) {
        return buildStatus(userId, now);
    }

    public boolean requiresActivation(Long userId) {
        BetaAccessVO status = getAccessStatus(userId);
        return status.isActivationEnabled() && !status.isAccessActive();
    }

    public void ensureAutoGrant(Long userId) {
        ensureAutoGrant(userId, new Date());
    }

    void ensureAutoGrant(Long userId, Date now) {
        if (userId == null || !betaAccessConfig.isAutoGrantActive(now)
                || productService.isPermanentUser(userId)) {
            return;
        }

        Object newLock = new Object();
        Object userLock = activationLocks.putIfAbsent(userId, newLock);
        userLock = userLock == null ? newLock : userLock;
        try {
            synchronized (userLock) {
                UserProductDO betaProduct = findAutoGrantProduct(userId);
                if (betaProduct == null) {
                    UserProductDO autoGrantedProduct = buildBetaProduct(userId, now);
                    try {
                        if (userProductMapper.insert(autoGrantedProduct) <= 0) {
                            throw new IllegalStateException("内测权限写入失败");
                        }
                    } catch (DuplicateKeyException duplicateKeyException) {
                        UserProductDO existingBeta = findBetaProduct(userId);
                        if (existingBeta == null) {
                            throw duplicateKeyException;
                        }
                        Date autoGrantUntil = betaAccessConfig.getAutoGrantUntil();
                        Date currentExpiry = existingBeta.getPeriodOfValidityEndTime();
                        if (autoGrantUntil != null && (currentExpiry == null || currentExpiry.before(autoGrantUntil))) {
                            existingBeta.setPeriodOfValidityEndTime(autoGrantUntil);
                            userProductMapper.updateById(existingBeta);
                        }
                    }
                    return;
                }

                Date autoGrantUntil = betaAccessConfig.getAutoGrantUntil();
                Date currentExpiry = betaProduct.getPeriodOfValidityEndTime();
                if (autoGrantUntil != null && (currentExpiry == null || currentExpiry.before(autoGrantUntil))) {
                    betaProduct.setPeriodOfValidityEndTime(autoGrantUntil);
                    userProductMapper.updateById(betaProduct);
                }
            }
        } finally {
            activationLocks.remove(userId, userLock);
        }
    }

    private BetaAccessVO buildStatus(Long userId, Date now) {
        return buildStatus(findBetaProduct(userId), productService.isPermanentUser(userId),
                productService.hasAnyValidProduct(userId), now);
    }

    private BetaAccessVO buildStatus(UserProductDO betaProduct, boolean permanent, Date now) {
        return buildStatus(betaProduct, permanent, false, now);
    }

    private BetaAccessVO buildStatus(UserProductDO betaProduct, boolean permanent, boolean paidAccess, Date now) {
        BetaAccessVO status = new BetaAccessVO();
        status.setPermanent(permanent);
        boolean activationEnabled = betaAccessConfig.isAutoGrantActive(now);
        status.setActivationEnabled(activationEnabled);
        status.setBetaActivated(betaProduct != null);
        if (betaProduct != null) {
            status.setPeriodOfValidityEndTime(betaProduct.getPeriodOfValidityEndTime());
        }
        boolean activeBeta = betaProduct != null
                && betaProduct.getPeriodOfValidityEndTime() != null
                && betaProduct.getPeriodOfValidityEndTime().after(now);
        status.setAccessActive(permanent || activeBeta || paidAccess);
        return status;
    }

    private UserProductDO buildBetaProduct(Long userId, Date activatedAt) {
        UserProductDO betaProduct = new UserProductDO();
        betaProduct.setUserId(userId);
        betaProduct.setOrderId(AUTO_GRANT_ORDER_ID);
        betaProduct.setProductId(ProductEnum.BETA.getCode().longValue());
        betaProduct.setProductType(ProductEnum.BETA.getProductTypes().toString());
        betaProduct.setPeriodOfValidityStartTime(activatedAt);
        betaProduct.setPeriodOfValidityEndTime(betaAccessConfig.resolveExpiry(activatedAt));
        return betaProduct;
    }

    private UserProductDO findBetaProduct(Long userId) {
        return findLatestBetaProduct(selectBetaProducts(userId, false));
    }

    private UserProductDO findAutoGrantProduct(Long userId) {
        return findLatestBetaProduct(selectBetaProducts(userId, true));
    }

    private List<UserProductDO> selectBetaProducts(Long userId, boolean autoGrantOnly) {
        LambdaQueryWrapper<UserProductDO> condition = new LambdaQueryWrapper<>();
        condition.eq(UserProductDO::getUserId, userId);
        condition.eq(UserProductDO::getProductId, ProductEnum.BETA.getCode().longValue());
        if (autoGrantOnly) {
            condition.eq(UserProductDO::getOrderId, AUTO_GRANT_ORDER_ID);
        }
        return userProductMapper.selectList(condition);
    }

    private UserProductDO findLatestBetaProduct(List<UserProductDO> betaProducts) {
        return betaProducts.stream()
                .max(Comparator.comparing(
                        UserProductDO::getPeriodOfValidityEndTime,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }
}
