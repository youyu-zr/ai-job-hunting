package com.maple.ai.job.hunting.frame.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;


/**
 * @author maple
 * desc: 用户在所有请求的响应中都添加跨域响应头
 *
 * 安全约束：这里必须回显白名单内的 Origin，不能无条件回显请求头 Origin。
 * 带 Access-Control-Allow-Credentials: true 时无条件回显，等于允许任意站点
 * 携带受害者 Cookie 读取本服务响应。
 */

@Order(2)
@Component
public class CorsFilter implements Filter {

    private static final Set<String> DEFAULT_ALLOWED_ORIGINS = new LinkedHashSet<>(Arrays.asList(
            "https://aijob.zxpnb.top",
            "https://www.zhipin.com",
            "http://localhost:5173",
            "http://localhost:4173",
            "http://127.0.0.1:5173",
            "http://127.0.0.1:4173"
    ));

    /**
     * 额外允许的 Origin（逗号分隔）。用于换域名或临时联调，无需改代码。
     */
    @Value("${cors.allowed-origins:}")
    private String extraAllowedOrigins;

    private Set<String> resolveAllowedOrigins() {
        Set<String> allowed = new LinkedHashSet<>(DEFAULT_ALLOWED_ORIGINS);
        if (StringUtils.hasText(extraAllowedOrigins)) {
            for (String origin : extraAllowedOrigins.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty()) {
                    allowed.add(trimmed);
                }
            }
        }
        return allowed;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletResponse response = (HttpServletResponse) res;
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String origin = httpRequest.getHeader("Origin");

        if (StringUtils.hasText(origin) && resolveAllowedOrigins().contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Max-Age", "3600");
            // 跨域时需要暴露自定义header
            response.setHeader("Access-Control-Expose-Headers", "Authorization, Retry-After");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Boss-Token, Zp_token");
            response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS, DELETE, PUT");
            // 命中白名单时缓存整个响应，避免同一响应在不同 Origin 下被 CDN 混用
            response.setHeader("Vary", "Origin");

            boolean preflight = "OPTIONS".equalsIgnoreCase(httpRequest.getMethod())
                    && StringUtils.hasText(httpRequest.getHeader("Access-Control-Request-Method"));
            if (preflight) {
                response.setHeader("Vary",
                        "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        Filter.super.destroy();
    }
}
