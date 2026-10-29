package com.maple.ai.job.hunting.frame.filter;

import cn.hutool.json.JSONUtil;
import com.maple.ai.job.hunting.frame.GlobalExceptionHandler;
import com.maple.ai.job.hunting.frame.exp.ApplicationException;
import com.maple.ai.job.hunting.model.common.Response;
import jakarta.annotation.Resource;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;


import java.io.IOException;
import java.io.PrintWriter;

/**
 * @author maple
 * Created Date: 2023/12/25 10:24
 * Description:
 */

@Order(1)
@Component
public class GlobalFilter implements Filter {

    @Resource
    private GlobalExceptionHandler globalExceptionHandler;

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
        HttpServletResponse response = (HttpServletResponse) resp;
        HttpServletRequest request = (HttpServletRequest) req;

        if (isNonCanonicalPath(request.getRequestURI())) {
            // Login, rate-limit and product filters match the raw URI exactly; Spring MVC would
            // still route "/api/x;foo" or "/api/%78" to the same handler, skipping those checks.
            response.setStatus(400);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write(JSONUtil.toJsonStr(Response.error(400, "请求路径不合法")));
            return;
        }

        Response<?> respModel = null;
        try {
            chain.doFilter(req, resp);
        } catch (Exception e) {
            if (e instanceof ApplicationException) {
                respModel = globalExceptionHandler.handlerAppException((ApplicationException) e);
            } else {
                respModel = globalExceptionHandler.handlerOtherException(e, request.getRequestURI());
            }
        }

        if (respModel != null) {
            response.setContentType("application/json;charset=utf-8");
            PrintWriter responseWriter = response.getWriter();
            responseWriter.write(JSONUtil.toJsonStr(respModel));
        }
    }

    static boolean isNonCanonicalPath(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.indexOf(';') >= 0
                || uri.indexOf('%') >= 0
                || uri.indexOf('\\') >= 0
                || uri.contains("//")
                || uri.contains("/./")
                || uri.contains("/../")
                || uri.endsWith("/.")
                || uri.endsWith("/..");
    }
}
