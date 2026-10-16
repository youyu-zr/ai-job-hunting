package com.maple.ai.job.hunting.controller;

import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.model.common.Response;
import com.maple.ai.job.hunting.model.vo.BetaAccessVO;
import com.maple.ai.job.hunting.service.biz.BetaAccessService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/beta")
public class BetaAccessController {

    @Resource
    private BetaAccessService betaAccessService;

    @GetMapping("/access")
    public Response<BetaAccessVO> getAccessStatus() {
        return Response.success(betaAccessService.getAccessStatus(HeaderContext.getHeader().getUserId()));
    }
}
