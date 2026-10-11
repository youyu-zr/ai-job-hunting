package com.maple.ai.job.hunting.config;

import com.maple.ai.job.hunting.controller.ALiPayController;
import com.maple.ai.job.hunting.service.biz.ALiPayService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentDisabledByDefaultTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentBeans.class);

    @Test
    void paymentControllerAndScheduledServiceAreAbsentByDefault() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(ALiPayController.class));

        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(ALiPayService.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ALiPayController.class, ALiPayService.class})
    static class PaymentBeans {
    }
}
