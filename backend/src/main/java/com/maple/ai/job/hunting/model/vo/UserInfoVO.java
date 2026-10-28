package com.maple.ai.job.hunting.model.vo;

import com.maple.ai.job.hunting.model.common.BaseVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * @author maple
 * Created Date: 2024/5/14 10:54
 * Description:
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UserInfoVO extends BaseVO {

    private Long id;

    private String phone;

    private String email;

    private String resumeId;

    /**
     * 用户偏好设置
     */
    private Object preference;

    /**
     * AI坐席状态
     */
    private Boolean aiSeatStatus;

    /**
     * 自己的邀请码
     */
    private String inviteCode;
    /**
     * 绑定的邀请码
     */
    private String bindInviteCode;

    /**
     * Whether BOSS actually confirmed that the caller owns this account.
     *
     * <p>Observability only. This is <strong>not</strong> a permission gate: under lenient mode the
     * provider cannot decide for anybody, so every session is unverified and a gate built on this
     * field would lock out every tester. Read it to know how a session was established; do not
     * branch product behaviour on it.
     *
     * <p>The boundary that does hold is at login: an unverified login may resume an account that
     * already exists, but may never create one.
     */
    private Boolean ownershipVerified;

    public UserInfoVO(String phone, String email) {
        this.phone = phone;
        this.email = email;
    }
}
