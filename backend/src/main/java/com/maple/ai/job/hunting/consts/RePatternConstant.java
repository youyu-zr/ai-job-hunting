package com.maple.ai.job.hunting.consts;

import java.util.regex.Pattern;

/**
 * @author maple
 * Created Date: 2024/5/13 16:14
 * Description:
 */

public class RePatternConstant {

    // 编译正则表达式
    public static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(1[34578]\\d{9})(?!\\d)");

    public static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Za-z0-9_]{1,128}@[A-Za-z0-9_]{1,128}\\.[A-Za-z]{1,32})(?![A-Za-z0-9_])");
}
