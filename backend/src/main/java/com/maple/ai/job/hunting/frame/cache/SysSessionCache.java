package com.maple.ai.job.hunting.frame.cache;

import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * @author yangfeng
 * @date : 2023/11/29 17:25
 * desc:
 */

@Component
public class SysSessionCache extends AbsMemoryCache<String, UserInfoVO> {
    public SysSessionCache() {
        // 滑动续期：只要页面还在用，会话就不过期；闲置 2 小时才失效，失效后前端会自动重登。
        super(2, TimeUnit.HOURS, true);
    }
}
