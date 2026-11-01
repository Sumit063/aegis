package dev.aegis.adapter.in.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * When a record cannot be processed (typically the database is down), retry it with backoff for as
 * long as it takes: the partition pauses and messages wait in Kafka (HLD §9). Nothing is skipped.
 */
@Configuration(proxyBeanMethods = false)
class CheckerErrorHandling {

    @Bean
    CommonErrorHandler checkerErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(30_000);
        return new DefaultErrorHandler(backOff);
    }
}
