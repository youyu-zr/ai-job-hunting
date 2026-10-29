package com.maple.ai.job.hunting.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationGateUtilOutboundContactTest {

    private static boolean hit(String answer) {
        return ConversationGateUtil.containsOutboundContact(answer);
    }

    @Test
    void concreteContactsAreDetected() {
        assertTrue(hit("我的电话13800138000"));
        assertTrue(hit("电话 138-0013-8000"));
        assertTrue(hit("邮箱 zhang.san@example.com"));
        assertTrue(hit("微信号：zhangsan_2024"));
        assertTrue(hit("QQ 123456789"));
    }

    @Test
    void talkingAboutContactWithoutAValueIsFine() {
        assertFalse(hit("可以加微信沟通，您方便时发我一下"));
        assertFalse(hit("期望薪资 8000-10000，2024 年毕业"));
        assertFalse(hit("微信沟通也可以的"));
        assertFalse(hit("工号 20241234567890 不是手机号"));
        assertFalse(hit(""));
    }

    @Test
    void commonLabelledWritingsAreCaught() {
        assertTrue(hit("WeChat ID: zhangsan88"));
        assertTrue(hit("我微信号为：zhangsan88"));
        assertTrue(hit("微信是：zhangsan88"));
        assertTrue(hit("微信号码：zhangsan88"));
        assertTrue(hit("微信号 zhangsan88"));
        assertTrue(hit("微信：zhangsan"));
        // 模型常见的加粗、引号、括号
        assertTrue(hit("微信：**lisi_99**"));
        assertTrue(hit("微信号：`lisi_99`"));
        assertTrue(hit("微信号「lisi_99」"));
        assertTrue(hit("微信号: \"lisi_99\""));
    }

    @Test
    void qqWithHaoIsCaught() {
        assertTrue(hit("QQ号：12345678"));
        assertTrue(hit("QQ号码 12345678"));
        assertTrue(hit("qq号是12345678"));
        assertFalse(hit("QQ音乐 2019 年上线的功能"));
    }

    @Test
    void phoneWritingsThatUsedToSlipThroughAreCaught() {
        assertTrue(hit("+8613800138000"));
        assertTrue(hit("8613800138000"));
        assertTrue(hit("+86 138 0013 8000"));
        assertTrue(hit("138  0013  8000"));
        assertTrue(hit("电话１３８００１３８０００"));
    }

    @Test
    void techAnswersAreNotHeld() {
        assertFalse(hit("用 wxWidgets 写过界面"));
        assertFalse(hit("wxPython4 也用过"));
        assertFalse(hit("熟悉 vxe-table 组件，vxe_table 二次封装"));
        assertFalse(hit("做过 WeChat MiniProgram"));
        assertFalse(hit("负责微信 Android 端，做过微信 Android13 适配"));
        assertFalse(hit("对接过微信 access_token、微信 open_id / union_id"));
        assertFalse(hit("微信（uni-app）小程序，微信（Kotlin）客户端"));
        assertFalse(hit("微信是 WeChat 的中文名"));
        assertFalse(hit("接入 wechatpay-apiv3"));
        assertFalse(hit("服务部署在 192.168.10.100"));
        assertFalse(hit("Java8、Vue3、K8s、H5 都熟"));
    }

    @Test
    void ownWechatWrittenWithoutALabelIsCaught() {
        assertFalse(hit("加我 zhangsan88 吧"));
        assertTrue(ConversationGateUtil.containsOutboundContact("加我 zhangsan88 吧", "zhangsan88"));
        assertTrue(ConversationGateUtil.containsOutboundContact("vx ZhangSan88", "zhangsan88"));
        // 只在整词出现时才算
        assertFalse(ConversationGateUtil.containsOutboundContact("xzhangsan88x", "zhangsan88"));
    }

    @Test
    void placeholdersAreFilledOnlyWithTheOwnersNumbers() {
        assertEquals("微信 zhangsan88，电话 19912345678",
                ConversationGateUtil.fillOwnContact("微信 【我的微信】，电话 【我的手机】", "zhangsan88", "19912345678"));
        assertEquals("加我zhangsan88", ConversationGateUtil.fillOwnContact("加我【我的微信号】", "zhangsan88", ""));
        // 号码没有时不能把占位符原样发给 HR
        assertNull(ConversationGateUtil.fillOwnContact("加我【我的微信】", "", "19912345678"));
        assertNull(ConversationGateUtil.fillOwnContact("电话【我的手机】", "zhangsan88", ""));
        assertFalse(hit("微信 【我的微信】"));
    }

    @Test
    void ownNumbersWrittenApartAreCaught() {
        String wx = "zhangsan88";
        String phone = "13800138000";
        assertTrue(ConversationGateUtil.containsOutboundContact("手机 一三八零零一三八零零零", wx, phone));
        assertTrue(ConversationGateUtil.containsOutboundContact("手机 138.0013.8000", wx, phone));
        assertTrue(ConversationGateUtil.containsOutboundContact("手机 138   0013   8000", wx, phone));
        assertTrue(ConversationGateUtil.containsOutboundContact("微信是 z h a n g s a n 8 8", wx, phone));
        assertFalse(ConversationGateUtil.containsOutboundContact("2024 年做过 3 个项目，涨了 15%", wx, phone));
    }

    @Test
    void miniProgramTemplateAndTechWordsAfterColonAreNotHeld() {
        assertFalse(hit("列表用 wx:for-item 渲染，wx:if 控制显示"));
        assertFalse(hit("熟悉微信：OAuth2 登录"));
        assertFalse(hit("对接过微信 ID：openid 和 unionid"));
    }

    @Test
    void unsafePlaceholdersAreNotSent() {
        // 紧挨字母数字：替换后会拼出别的账号
        assertNull(ConversationGateUtil.fillOwnContact("微信：li【我的微信】_hr", "zhangsan88", ""));
        assertNull(ConversationGateUtil.fillOwnContact("电话 1【我的手机】", "", "13800138000"));
        // 认不出的变体不能原样发给 HR
        assertNull(ConversationGateUtil.fillOwnContact("微信【我的微信号码】", "zhangsan88", ""));
        assertNull(ConversationGateUtil.fillOwnContact("加我[我的VX]", "zhangsan88", ""));
        assertTrue(ConversationGateUtil.containsOwnContactPlaceholder("加我[我的VX]"));
    }

    @Test
    void longInputsStayFast() {
        String noAt = "a".repeat(100_000);
        long start = System.nanoTime();
        hit(noAt);
        hit("微信" + " ".repeat(100_000) + "x");
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1000);
    }
}
