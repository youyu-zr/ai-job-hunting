package com.maple.ai.job.hunting.service.biz;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maple.ai.job.hunting.emums.ProductEnum;
import com.maple.ai.job.hunting.emums.ProductTypeEnum;
import com.maple.ai.job.hunting.config.AppBizConfig;
import com.maple.ai.job.hunting.mapper.UserProductMapper;
import com.maple.ai.job.hunting.model.bo.UserProductDO;
import com.maple.ai.job.hunting.model.vo.ProductVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * @author maple
 * Created Date: 2024/6/5 17:15
 * Description:
 */

@Service
@Slf4j
public class ProductService {

    @Resource
    private UserProductMapper userProductMapper;

    @Resource
    private AppBizConfig appBizConfig;

    public List<ProductVO> getUserProductList(Long userId) {

        // 获取用户所有的产品
        LambdaQueryWrapper<UserProductDO> condition = new LambdaQueryWrapper<>();
        condition.eq(UserProductDO::getUserId, userId);
        List<UserProductDO> userProductDOList = userProductMapper.selectList(condition);
        if (CollectionUtils.isEmpty(userProductDOList)) {
            return Collections.emptyList();
        }

        // 过期产品最多显示4个，删除最早的过期产品
        Date currentDate = new Date();
        long expireCount = userProductDOList.stream().map(UserProductDO::getPeriodOfValidityEndTime).filter(endTime -> endTime.before(currentDate)).count();
        List<Long> waitDeleteIdList = new ArrayList<>();
        for (UserProductDO userProductDO : userProductDOList) {
            Date endTime = userProductDO.getPeriodOfValidityEndTime();
            if (endTime.before(currentDate) && expireCount - waitDeleteIdList.size() > 2) {
                waitDeleteIdList.add(userProductDO.getId());
            }
        }

        // 删除过期产品
        userProductDOList.removeIf(userProductDO -> waitDeleteIdList.contains(userProductDO.getId()));
        // 倒序排序（最近时间的产品排在前面）
        Collections.reverse(userProductDOList);

        // 转换对象
        return userProductDOList.stream().map(userProductDO -> {
            List<Integer> productTypeCodeList = Optional.ofNullable(userProductDO.getProductType())
                    .filter(JSON::isValid)
                    .map(str -> JSON.parseArray(str, Integer.class))
                    .orElse(Collections.emptyList());
            List<String> productPowerList = productTypeCodeList.stream().map(ProductTypeEnum::getByCode)
                    .map(ProductTypeEnum::getDesc).toList();
            List<ProductVO> productVOList = new ArrayList<>(userProductDOList.size());
            ProductVO productVO = BeanUtil.copyProperties(userProductDO, ProductVO.class);
            productVO.setProductName(ProductEnum.getByCode(userProductDO.getProductId().intValue()).getDesc());
            productVO.setPowerList(productPowerList);
            productVOList.add(productVO);
            return productVOList;
        }).flatMap(Collection::stream).toList();
    }


    public Set<Integer> queryUserValidAllProductType(Long userId) {
        Set<Integer> productTypes = new HashSet<>(userProductMapper.queryUserValidAllProductType(userId));
        if (isPermanentUser(userId)) {
            productTypes.addAll(ProductEnum.BETA.getProductTypes());
        }
        return productTypes;
    }

    /**
     * 当前免费阶段：任何已登录用户都拥有全部功能，不随内测日期到期。
     * 产品/试用体系计划在后续清理中整体删除。
     */
    public boolean isPermanentUser(Long userId) {
        return userId != null;
    }

    public boolean hasAnyValidProduct(Long userId) {
        return !userProductMapper.queryUserValidAllProduct(userId).isEmpty();
    }

    /**
     * 用户是否有指定产品能力权限
     *
     * @param userId      用户 ID
     * @param productType 产品类型
     * @return boolean
     */
    public boolean hasProductAbility(Long userId, Integer productType) {
        return queryUserValidAllProductType(userId).contains(productType);
    }

    /**
     * 用户是否有指定产品能力权限
     *
     * @param userId      用户 ID
     * @param productType 产品类型
     * @return boolean
     */
    public boolean hasProductAbilityAll(Long userId, ProductTypeEnum ... productType) {
        List<Integer> list = Arrays.stream(productType).map(ProductTypeEnum::getCode).toList();
        return queryUserValidAllProductType(userId).containsAll(list);
    }
}
