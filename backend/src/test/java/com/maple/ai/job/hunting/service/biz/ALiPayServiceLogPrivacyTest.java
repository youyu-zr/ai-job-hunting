package com.maple.ai.job.hunting.service.biz;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.maple.ai.job.hunting.mapper.OrderMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class ALiPayServiceLogPrivacyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "missing-order-123",
            "missing-order\r\nINFO forged-payment-success",
            "missing-order\u2028forged-payment-success"
    })
    void missingOrderDoesNotWriteExternalOrderIdToLogs(String orderId) {
        ALiPayService service = new ALiPayService();
        ReflectionTestUtils.setField(service, "orderMapper", mock(OrderMapper.class));
        Logger logger = (Logger) LoggerFactory.getLogger(ALiPayService.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ERROR);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            ReflectionTestUtils.invokeMethod(service, "handlerOrderPaySuccess", orderId);

            assertEquals(1, appender.list.size());
            String message = appender.list.get(0).getFormattedMessage();
            assertFalse(message.contains(orderId));
            assertFalse(message.contains("forged-payment-success"));
            assertFalse(message.contains("\r"));
            assertFalse(message.contains("\n"));
            assertFalse(message.contains("\u2028"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
            ScheduledExecutorService executor =
                    (ScheduledExecutorService) ReflectionTestUtils.getField(service, "scheduledExecutorService");
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }
}
