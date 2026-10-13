package com.maple.ai.job.hunting.service.biz;

import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.emums.ProductTypeEnum;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.mapper.UserResumeMapper;
import com.maple.ai.job.hunting.model.bo.UserResumeDO;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static java.util.Set.of;
import static org.mockito.Mockito.when;

class UserServiceResumeGateTest {

    @BeforeAll
    static void initializeMyBatisEntityMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "resume-gate-test"),
                UserInfoDO.class);
    }

    @AfterEach
    void clearHeader() {
        HeaderContext.clear();
    }

    @Test
    void cannotEnableAiSeatWithoutAnImportedResume() {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.setHeader(new Header(user, "request", "/api/user/save/preference", null, false, null, null));

        UserResumeMapper resumes = mock(UserResumeMapper.class);
        UserInfoMapper users = mock(UserInfoMapper.class);
        when(resumes.selectByUserId(42L)).thenReturn(null);
        UserService service = service(resumes, users);
        UserInfoVO update = new UserInfoVO();
        update.setAiSeatStatus(true);

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.savePreference(update));

        assertEquals("请先导入简历", error.getMessage());
        verify(users, never()).update(any(), any());
    }

    @Test
    void blankImportedResumeCannotEnableAiSeat() {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.setHeader(new Header(user, "request", "/api/user/save/preference", null, false, null, null));

        UserResumeMapper resumes = mock(UserResumeMapper.class);
        UserInfoMapper users = mock(UserInfoMapper.class);
        when(resumes.selectByUserId(42L)).thenReturn(new UserResumeDO(42L, "  ", null, "resume-1"));
        UserService service = service(resumes, users);
        UserInfoVO update = new UserInfoVO();
        update.setAiSeatStatus(true);

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.savePreference(update));

        assertEquals("请先导入简历", error.getMessage());
        verify(users, never()).update(any(), any());
    }

    @Test
    void disablingAiSeatDoesNotRequireAnImportedResume() {
        UserInfoVO user = new UserInfoVO();
        user.setId(42L);
        HeaderContext.setHeader(new Header(user, "request", "/api/user/save/preference", null, false, null, null));

        UserResumeMapper resumes = mock(UserResumeMapper.class);
        UserInfoMapper users = mock(UserInfoMapper.class);
        UserService service = service(resumes, users);
        UserInfoVO update = new UserInfoVO();
        update.setAiSeatStatus(false);

        service.savePreference(update);

        verify(resumes, never()).selectByUserId(42L);
        verify(users).update(any(), any());
    }

    private static UserService service(UserResumeMapper resumes, UserInfoMapper users) {
        UserService service = new UserService();
        ReflectionTestUtils.setField(service, "userResumeMapper", resumes);
        ReflectionTestUtils.setField(service, "userInfoMapper", users);
        ProductService products = new ProductService() {
            @Override
            public java.util.Set<Integer> queryUserValidAllProductType(Long userId) {
                return of(ProductTypeEnum.AI_SEAT.getCode());
            }
        };
        ReflectionTestUtils.setField(service, "productService", products);
        ReflectionTestUtils.setField(service, "betaAccessService", new BetaAccessService(null, null, null) {
            @Override
            public boolean requiresActivation(Long userId) {
                return false;
            }
        });
        return service;
    }
}
