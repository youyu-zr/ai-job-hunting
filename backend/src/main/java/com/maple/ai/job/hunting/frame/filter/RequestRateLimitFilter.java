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

    @Value("${ai.request.max-concurrent:128}")
    private int configuredMaxConcurrentRequests = 128;

    private volatile Semaphore activeModelRequests = new Semaphore(128, true);

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

        // Take the model slot first: a request turned away for lack of a slot must not also burn
        // the user's own quota, otherwise a busy server makes the retry fail a second time.
        boolean modelSlotAcquired = false;
        if (rule.modelRequest) {
            modelSlotAcquired = activeModelRequests.tryAcquire();
            if (!modelSlotAcquired) {
                reject(response, 1.0);
                return;
            }
        }

        String key = rateLimitKey(request, rule.anonymousIdentity, bossToken);
        if (!tryAcquire(key, rule.permitsPerSecond)) {
            if (modelSlotAcquired) {
                activeModelRequests.release();
            }
            reject(response, rule.permitsPerSecond);
            return;
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

    /**
     * These limits only exist to stop a runaway loop (a bug that fires the same request forever) or
     * scripted abuse. They are set far above anything a person driving a browser tab can produce:
     * nginx logs 2026-10-14..28 show the busiest single IP doing 4 logins in one second / 27 in a
     * minute, and the other routes are bounded by BOSS page actions (one message, one job card).
     * The upstream model provider (SiliconFlow, per-account RPM 1000+) is the real ceiling.
     */
    private static Rule ruleFor(String uri) {
        return switch (uri) {
            // Login is not a model request: making it compete for a model concurrency slot means
            // a busy AI queue locks users out of the product entirely.
            case "/api/user/silently/login" -> new Rule(10.0, 100.0, true, false);
            // Resume import IS a model request on the fallback path: when local text extraction
            // finds nothing usable, UserService hands the file to aiServiceFacade.readFile. Dropping
            // the concurrency slot here would let this route bypass ai.request.max-concurrent.
            case "/api/user/import/resume" -> new Rule(1.0, 10.0, false, true);
            case "/api/user/ai/config/test" -> new Rule(1.0, 0, false, true);
            case "/api/job/filter/one" -> new Rule(20.0, 0, false, true);
            case "/api/job/ai/assistant/generate/greeting" -> new Rule(10.0, 0, false, true);
            case "/api/job/seeker/cloned/ask" -> new Rule(30.0, 0, false, true);
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
