package com.maple.ai.job.hunting.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationGateUtilOutboundContactTest {

    @Test
    void concreteContactsAreDetected() {
        assertTrue(ConversationGateUtil.containsOutboundContact("我的电话13800138000"));
        assertTrue(ConversationGateUtil.containsOutboundContact("电话 138-0013-8000"));
        assertTrue(ConversationGateUtil.containsOutboundContact("邮箱 zhang.san@example.com"));
        assertTrue(ConversationGateUtil.containsOutboundContact("微信号：zhangsan_2024"));
        assertTrue(ConversationGateUtil.containsOutboundContact("vx zhangsan88"));
        assertTrue(ConversationGateUtil.containsOutboundContact("QQ 123456789"));
    }

    @Test
    void talkingAboutContactWithoutAValueIsFine() {
        assertFalse(ConversationGateUtil.containsOutboundContact("可以加微信沟通，您方便时发我一下"));
        assertFalse(ConversationGateUtil.containsOutboundContact("期望薪资 8000-10000，2024 年毕业"));
        assertFalse(ConversationGateUtil.containsOutboundContact("微信沟通也可以的"));
        assertFalse(ConversationGateUtil.containsOutboundContact("工号 20241234567890 不是手机号"));
        assertFalse(ConversationGateUtil.containsOutboundContact(""));
    }
}
