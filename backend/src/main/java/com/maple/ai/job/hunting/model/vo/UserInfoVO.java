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

    /** 只在导入简历的返回里有值：这次从简历里识别并自动填进偏好的微信号，给用户核对。 */
    private String wechatId;

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

    public UserInfoVO(String phone, String email) {
        this.phone = phone;
        this.email = email;
    }
}
