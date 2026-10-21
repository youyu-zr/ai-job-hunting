package com.maple.ai.job.hunting.controller;

import com.maple.ai.job.hunting.emums.BizCodeEnum;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.model.common.Response;
import com.maple.ai.job.hunting.model.vo.UserInfoVO;
import com.maple.ai.job.hunting.service.biz.UserService;
import com.maple.ai.job.hunting.utils.BossTokenHeaders;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * @author maple
 * Created Date: 2024/5/9 16:21
 * Description:
 */

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Resource
    private UserService userService;


    /**
     * 导入简历
     *
     * @param file     简历文件
     * @param resumeId boss平台的简历id
     * @return {@link Response }<{@link UserInfoVO }>
     * @throws Exception 例外
     */
    @PostMapping("/import/resume")
    public Response<UserInfoVO> importResume(MultipartFile file, String resumeId) throws Exception {
        if (file == null) {
            throw new ApplicationException(BizCodeEnum.PARAM_ERROR);
        }
        InputStream resumeFileStream = file.getInputStream();

        return Response.success(userService.importResume(resumeFileStream, resumeId));
    }


    /**
     * 静默登录
     *
     * @param uniqueId uniqueId boss平台id
     * @return {@link String}
     */
    @PostMapping("/silently/login")
    public Response<String> loginSilently(@RequestParam(required = false) String uniqueId,
                                         @RequestHeader(value = BossTokenHeaders.CANONICAL_HEADER, required = false) String canonicalBossToken,
                                         @RequestHeader(value = BossTokenHeaders.LEGACY_HEADER, required = false) String legacyBossToken) {
        return Response.success(userService.loginSilently(uniqueId,
                BossTokenHeaders.resolve(canonicalBossToken, legacyBossToken)));
    }

    @PostMapping("/userinfo")
    public Response<UserInfoVO> getUserInfo() {
        return Response.success(userService.getUserInfo());
    }


    @PostMapping("/save/preference")
    public Response<?> savePreference(@RequestBody UserInfoVO userInfoVO) {
        userService.savePreference(userInfoVO);
        return Response.success();
    }
}
