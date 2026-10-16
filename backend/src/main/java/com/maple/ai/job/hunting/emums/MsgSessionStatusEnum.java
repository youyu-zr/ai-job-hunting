package com.maple.ai.job.hunting.emums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * @author maple
 * Created Date: 2024/5/10 11:35
 * Description:
 */

@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum MsgSessionStatusEnum {

    /**
     *
     */
    NULL,
    IN_USE(1, "使用中"),
    MANUALLY_PAUSED(3, "人工暂停"),
    /**
     *
     */
    EXPIRED(2, "已过期"),
    ;


    private Integer code;

    private String desc;

    private static final int STATE_RADIX = 4;

    public static int baseCode(Integer storedCode) {
        if (storedCode == null) {
            return NULL.code == null ? 0 : NULL.code;
        }
        return Math.floorMod(storedCode, STATE_RADIX);
    }

    public static long revision(Integer storedCode) {
        if (storedCode == null || storedCode < 0) {
            return 0L;
        }
        return storedCode / (long) STATE_RADIX;
    }

    public static int encode(long revision, MsgSessionStatusEnum status) {
        if (revision < 0 || revision > (Integer.MAX_VALUE - status.code) / STATE_RADIX) {
            throw new IllegalArgumentException("session status revision out of range");
        }
        return Math.toIntExact(revision * STATE_RADIX + status.code);
    }

    public static boolean is(Integer storedCode, MsgSessionStatusEnum status) {
        return baseCode(storedCode) == status.code;
    }


    public static MsgSessionStatusEnum getByDesc(String desc) {
        for (MsgSessionStatusEnum fileType : MsgSessionStatusEnum.values()) {
            if (fileType.desc != null && fileType.desc.equalsIgnoreCase(desc)) {
                return fileType;
            }
        }

        return MsgSessionStatusEnum.NULL;
    }

    public static MsgSessionStatusEnum getByCode(Integer code) {
        int baseCode = baseCode(code);
        for (MsgSessionStatusEnum fileType : MsgSessionStatusEnum.values()) {
            if (fileType.code != null && fileType.code.equals(baseCode)) {
                return fileType;
            }
        }

        return MsgSessionStatusEnum.NULL;
    }
}
