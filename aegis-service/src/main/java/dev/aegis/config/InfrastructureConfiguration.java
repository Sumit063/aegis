package dev.aegis.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared infrastructure beans. */
@Configuration(proxyBeanMethods = false)
class InfrastructureConfiguration {

    /** One clock for every timestamp, so tests can fix time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
