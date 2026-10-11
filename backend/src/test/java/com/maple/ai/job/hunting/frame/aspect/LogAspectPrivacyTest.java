package com.maple.ai.job.hunting.frame.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.maple.ai.job.hunting.common.Header;
import com.maple.ai.job.hunting.common.HeaderContext;
import com.maple.ai.job.hunting.frame.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogAspectPrivacyTest {

    @Test
    void requestArgumentsAndResponseBodiesAreNotWrittenToLogs() throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger(LogAspect.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        HttpServletRequest request = new MockHttpServletRequest();
        ((MockHttpServletRequest) request).setRequestURI("/api/user/silently/login");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        HeaderContext.setHeader(new Header(null, "test-request", request.getRequestURI(),
                null, false, "127.0.0.1", null));

        try {
            ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
            Object[] arguments = {"boss-session-secret"};
            when(joinPoint.getArgs()).thenReturn(arguments);
            when(joinPoint.proceed(any(Object[].class))).thenReturn("application-session-secret");

            new LogAspect().logAspect(joinPoint);

            String loggedText = appender.list.stream()
                    .map(event -> event.getFormattedMessage())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(loggedText.contains("boss-session-secret"));
            assertFalse(loggedText.contains("application-session-secret"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
            RequestContextHolder.resetRequestAttributes();
            HeaderContext.clear();
        }
    }

    @Test
    void exceptionMessagesAreNotWrittenToLogs() throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger(LogAspect.class);
        Logger exceptionLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level previousLevel = logger.getLevel();
        Level previousExceptionLevel = exceptionLogger.getLevel();
        logger.setLevel(Level.INFO);
        exceptionLogger.setLevel(Level.ERROR);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        exceptionLogger.addAppender(appender);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/user/ai/config/test");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        HeaderContext.setHeader(new Header(null, "test-request", request.getRequestURI(),
                null, false, "127.0.0.1", null));

        try {
            ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
            when(joinPoint.getArgs()).thenReturn(new Object[]{"api-key-secret"});
            when(joinPoint.proceed(any(Object[].class)))
                    .thenThrow(new IllegalStateException("sensitive-upstream-response"));

            LogAspect logAspect = new LogAspect();
            ReflectionTestUtils.setField(logAspect, "globalExceptionHandler", new GlobalExceptionHandler());
            logAspect.logAspect(joinPoint);

            String loggedText = appender.list.stream()
                    .map(event -> event.getFormattedMessage())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(loggedText.contains("api-key-secret"));
            assertFalse(loggedText.contains("sensitive-upstream-response"));
        } finally {
            logger.detachAppender(appender);
            exceptionLogger.detachAppender(appender);
            logger.setLevel(previousLevel);
            exceptionLogger.setLevel(previousExceptionLevel);
            appender.stop();
            RequestContextHolder.resetRequestAttributes();
            HeaderContext.clear();
        }
    }
}
