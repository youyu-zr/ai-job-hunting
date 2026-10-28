package com.maple.ai.job.hunting.service.biz;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserServiceAiSeatRevisionTest {

    @BeforeAll
    static void initializeMyBatisEntityMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "ai-seat-revision-test"),
                UserInfoDO.class);
    }

    @AfterEach
    void clearHeader() {
        HeaderContext.clear();
    }

    @Test
    void changingAiSeatStatusIncrementsAndPreservesServerOwnedPreferenceRevision() {
        HeaderContext.setHeader(header(42L));
        UserInfoMapper mapper = mock(UserInfoMapper.class);
        UserInfoDO current = new UserInfoDO();
        current.setId(42L);
        current.setAiSeatStatus(1);
        current.setPreference("{\"aiSeatRevision\":8,\"jobGoal\":\"old\"}");
        doReturn(current).when(mapper).selectByIdForUpdate(42L);

        UserService service = new UserService();
        ReflectionTestUtils.setField(service, "userInfoMapper", mapper);
        UserInfoVO update = new UserInfoVO();
        update.setAiSeatStatus(false);
        update.setPreference(Map.of("jobGoal", "new"));

        service.savePreference(update);

        verify(mapper).selectByIdForUpdate(42L);
        org.mockito.ArgumentCaptor<Wrapper<UserInfoDO>> wrapper =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(any(UserInfoDO.class), wrapper.capture());
        LambdaUpdateWrapper<UserInfoDO> updateWrapper =
                (LambdaUpdateWrapper<UserInfoDO>) wrapper.getValue();
        String persistedPreference = updateWrapper.getParamNameValuePairs().values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> value.contains("\"jobGoal\""))
                .findFirst().orElseThrow();
        com.alibaba.fastjson.JSONObject json =
                com.alibaba.fastjson.JSON.parseObject(persistedPreference);
        assertEquals(9L, json.getLongValue("aiSeatRevision"));
        assertEquals("new", json.getString("jobGoal"));
        assertTrue(updateWrapper.getParamNameValuePairs().values().contains(0));
    }
    private static String capturedPreference(UserInfoMapper mapper) {
        org.mockito.ArgumentCaptor<Wrapper<UserInfoDO>> wrapper =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(any(UserInfoDO.class), wrapper.capture());
        LambdaUpdateWrapper<UserInfoDO> updateWrapper =
                (LambdaUpdateWrapper<UserInfoDO>) wrapper.getValue();
        return updateWrapper.getParamNameValuePairs().values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(value -> value.contains("\"jobGoal\""))
                .findFirst().orElseThrow();
    }
    private static Header header(Long userId) {
        UserInfoVO user = new UserInfoVO();
        user.setId(userId);
        // Preference writes are reserved for sessions whose ownership BOSS actually proved.
        user.setOwnershipVerified(Boolean.TRUE);
        return new Header(user, "request", "/api/user/save/preference",
                null, false, null, null);
    }
}
