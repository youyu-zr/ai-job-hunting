package com.maple.ai.job.hunting.consts;

import java.util.regex.Pattern;

/**
 * @author maple
 * Created Date: 2024/5/13 16:14
 * Description:
 */

public class RePatternConstant {

    // 编译正则表达式
    // 1[3-9]：原来是 1[34578]，19x、16x 开头的号码（现在很常见）识别不出来。
    public static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");

    /**
     * 「标签 + 分隔 + 账号」：group(1) 是分隔部分，group(2) 是账号。简历识别和发送前兜底共用。
     * <ul>
     *   <li>英文标签（wechat/weixin/vx/wx）前面不能紧挨字母数字（排除 vxe-table、wxWidgets、devxlabs）；</li>
     *   <li>账号后面不能紧跟 @、或「点+字母数字」（排除 abc@qq.com；句末的英文句点不影响）；</li>
     *   <li>分隔部分限长 8 个字符，长空白串不会导致回溯变慢。</li>
     * </ul>
     * 只靠这个正则会把「微信 Android 开发」也当成账号，要再用 {@link #isLikelyWechatAccount} 判断。
     */
    public static final Pattern WECHAT_LABELLED = Pattern.compile(
            "(?i)(?:微\\s?信|v信|(?<![A-Za-z0-9])(?:wechat|weixin|vx|wx))"
                    + "\\s{0,3}((?:[\\s:：是为(（]|号码?|id){0,8})"
                    + "([A-Za-z][-_A-Za-z0-9]{5,19})(?![-_A-Za-z0-9@]|\\.[A-Za-z0-9])");

    /** 简历识别沿用的名字。 */
    public static final Pattern WECHAT_PATTERN = WECHAT_LABELLED;

    /** 「微信 xxx」后面跟的是技术名词时不是微信号（技术简历里很常见）。全小写比较。 */
    public static final java.util.Set<String> WECHAT_TECH_WORDS = java.util.Set.of(
            "miniprogram", "miniapp", "uniapp", "official", "openapi", "devtools", "payment", "jssdk",
            "weapp", "mpvue", "cloudbase", "webview", "account", "platform", "applet", "android",
            "native", "oauth2", "webhook", "flutter", "widgets", "server", "client", "plugin",
            "openid", "unionid", "appid", "appsecret", "access_token", "session_key");

    /**
     * 判断「标签 + 分隔 + 账号」是不是真的在写微信号。
     * 分隔里有冒号、「号/ID/是/为」、括号时（「微信：abc_12」「微信号 abcdef」）一定算；
     * 只有空格或什么都没有时（「微信 Android 端」「vx zhangsan88」），账号里得有数字或下划线才算——
     * 真实微信号几乎都带，技术名词基本不带。技术名词表里的词一律不算。
     */
    public static boolean isLikelyWechatAccount(String separator, String account) {
        if (account == null || WECHAT_TECH_WORDS.contains(account.toLowerCase())) {
            return false;
        }
        String sep = separator == null ? "" : separator.toLowerCase();
        boolean strong = sep.matches(".*(?:[:：是为(（]|号|id).*");
        return strong || account.matches(".*[0-9_].*");
    }

    /** 「微信同手机」「微信同号」「手机/微信」「微信：138…」：微信号就是手机号（微信可以用手机号搜到人）。 */
    public static final Pattern WECHAT_SAME_AS_PHONE_PATTERN = Pattern.compile(
            "(?i)微\\s?信\\s*+(?:号码?\\s*+)?[:：(（]?\\s*+(?:同手机号?|同号|同电话|=\\s*+手机)"
                    + "|(?:手机|电话)\\s*+[/／、&]\\s*+(?:微\\s?信|vx|wx)"
                    + "|(?:微\\s?信|vx|wx)\\s*+[/／、&]\\s*+(?:手机|电话)"
                    + "|(?:微\\s?信|vx|wx)(?:号码?)?\\s*+[:：]\\s*+1[3-9]\\d{9}(?!\\d)");

    public static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Za-z0-9_]{1,128}@[A-Za-z0-9_]{1,128}\\.[A-Za-z]{1,32})(?![A-Za-z0-9_])");
}
