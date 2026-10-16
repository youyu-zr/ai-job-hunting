package com.maple.ai.job.hunting.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maple.ai.job.hunting.model.bo.UserResumeDO;
import org.apache.ibatis.annotations.Insert;

/**
 * 用户简历表(UserResume)表数据库访问层
 *
 * @author makejava
 * @since 2024-05-13 15:49:51
 */
public interface UserResumeMapper extends BaseMapper<UserResumeDO> {


    default UserResumeDO selectByUserId(Long userId){
        return this.selectOne(new LambdaQueryWrapper<UserResumeDO>().eq(UserResumeDO::getUserId, userId));
    }

    @Insert("""
            INSERT INTO user_resume
                (user_id, resume_content, resume_url, oss_file_name, preset_problem, resume_id,
                 is_active, created_date, updated_date)
            VALUES
                (#{userId}, #{resumeContent}, NULL, #{ossFileName}, NULL, #{resumeId},
                 b'1', NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                resume_content = VALUES(resume_content),
                resume_url = NULL,
                oss_file_name = VALUES(oss_file_name),
                preset_problem = NULL,
                resume_id = VALUES(resume_id),
                is_active = b'1',
                updated_date = NOW()
            """)
    int saveLatestResume(UserResumeDO resume);

}

