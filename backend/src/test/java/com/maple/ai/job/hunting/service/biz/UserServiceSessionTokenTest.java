package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.frame.cache.SysSessionCache;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceSessionTokenTest {

    @Test
    void silentLoginReturnsCryptographicallyRandomOpaqueTokens() {
        UserInfoDO userInfo = new UserInfoDO();
        userInfo.setId(42L);
        userInfo.setUniqueId("boss-user");

        UserInfoMapper userInfoMapper = (UserInfoMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{UserInfoMapper.class},
                (proxy, method, args) -> "selectByUniqueId".equals(method.getName()) ? userInfo : null);
        BossIdentityVerifier bossIdentityVerifier = new BossIdentityVerifier() {
            @Override
            public void requireOwnership(String claimedUid, String bossToken) {
            }
        };
        SysSessionCache sysSessionCache = new SysSessionCache();
        UserService userService = new UserService();
        ReflectionTestUtils.setField(userService, "userInfoMapper", userInfoMapper);
        ReflectionTestUtils.setField(userService, "bossIdentityVerifier", bossIdentityVerifier);
        ReflectionTestUtils.setField(userService, "sysSessionCache", sysSessionCache);

        String firstToken = userService.loginSilently("boss-user", "boss-token");
        String secondToken = userService.loginSilently("boss-user", "boss-token");

        assertTrue(firstToken.matches("[A-Za-z0-9_-]{43}"));
        assertTrue(secondToken.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(firstToken, secondToken);
        assertNotNull(sysSessionCache.get(firstToken));
        assertNotNull(sysSessionCache.get(secondToken));
    }
}
