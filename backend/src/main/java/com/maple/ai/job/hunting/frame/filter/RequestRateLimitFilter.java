package com.maple.ai.job.hunting.frame.filter;

import cn.hutool.json.JSONUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.RateLimiter;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.model.common.Response;
import com.maple.ai.job.hunting.utils.BossTokenHeaders;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Order(4)
@Component
public class RequestRateLimitFilter implements Filter {

    private final Cache<String, RateLimiter> limiters = CacheBuilder.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    @Value("${ai.request.max-concurrent:32}")
    private int configuredMaxConcurrentRequests = 32;

    private volatile Semaphore activeModelRequests = new Semaphore(32, true);

    @PostConstruct
    void initializeConcurrencyLimit() {
        int limit = Math.max(1, Math.min(configuredMaxConcurrentRequests, 256));
        activeModelRequests = new Semaphore(limit, true);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        Rule rule = ruleFor(request.getRequestURI());
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String bossToken = rule.anonymousIdentity
                ? BossTokenHeaders.resolve(request.getHeader(BossTokenHeaders.CANONICAL_HEADER),
                        request.getHeader(BossTokenHeaders.LEGACY_HEADER))
                : null;
        if (rule.globalPermitsPerSecond > 0
                && !tryAcquire("route|" + request.getRequestURI(), rule.globalPermitsPerSecond)) {
            reject(response, rule.globalPermitsPerSecond);
            return;
        }

        String key = rateLimitKey(request, rule.anonymousIdentity, bossToken);
        if (!tryAcquire(key, rule.permitsPerSecond)) {
            reject(response, rule.permitsPerSecond);
            return;
        }

        boolean modelSlotAcquired = false;
        if (rule.modelRequest) {
            modelSlotAcquired = activeModelRequests.tryAcquire();
            if (!modelSlotAcquired) {
                // The identity permit has already been consumed, even without a model slot.
                reject(response, rule.permitsPerSecond);
                return;
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (modelSlotAcquired) {
                activeModelRequests.release();
            }
        }
    }

    boolean tryAcquire(String key, double permitsPerSecond) {
        RateLimiter limiter = limiters.getIfPresent(key);
        if (limiter == null) {
            try {
                limiter = limiters.get(key, () -> RateLimiter.create(permitsPerSecond));
            } catch (Exception e) {
                return false;
            }
        }
        return limiter.tryAcquire();
    }

    static double permitsPerSecond(String uri) {
        Rule rule = ruleFor(uri);
        return rule == null ? 0 : rule.permitsPerSecond;
    }

    static double globalPermitsPerSecond(String uri) {
        Rule rule = ruleFor(uri);
        return rule == null ? 0 : rule.globalPermitsPerSecond;
    }

    private static Rule ruleFor(String uri) {
        return switch (uri) {
            case "/api/user/silently/login" -> new Rule(0.2, 10.0, true, true);
            case "/api/user/import/resume" -> new Rule(0.1, 2.0, false, true);
            case "/api/user/ai/config/test" -> new Rule(0.1, 0, false, true);
            case "/api/job/filter/one",
                 "/api/job/ai/assistant/generate/greeting" -> new Rule(2.0, 0, false, true);
            case "/api/job/seeker/cloned/ask" -> new Rule(3.0, 0, false, true);
            default -> null;
        };
    }

    private static String rateLimitKey(HttpServletRequest request, boolean anonymousIdentity, String bossToken) {
        String identity;
        if (anonymousIdentity) {
            String uniqueId = request.getParameter("uniqueId");
            String remoteAddress = request.getRemoteAddr();
            identity = request.getRequestURI() + "|" + remoteAddress + "|"
                    + (uniqueId == null ? "" : uniqueId.substring(0, Math.min(uniqueId.length(), 64)))
                    + "|" + (bossToken == null ? "" : bossToken);
        } else {
            identity = request.getRequestURI() + "|" + HeaderContext.getHeader().getUserId();
        }
        return Hashing.sha256().hashString(identity, StandardCharsets.UTF_8).toString();
    }

    private static void reject(HttpServletResponse response, double permitsPerSecond) throws IOException {
        response.setStatus(429);
        long retryAfterSeconds = Math.max(1L, (long) Math.ceil(1.0 / permitsPerSecond));
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(JSONUtil.toJsonStr(Response.error(429, "请求过于频繁，请稍后重试")));
    }

    private record Rule(
            double permitsPerSecond,
            double globalPermitsPerSecond,
            boolean anonymousIdentity,
            boolean modelRequest) {
    }
}
