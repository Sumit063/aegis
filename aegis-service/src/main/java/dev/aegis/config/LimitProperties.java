package dev.aegis.config;

import dev.aegis.application.service.IngressLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code aegis.limits.*}: bounds on public traffic per connection; defaults in {@link IngressLimits}. */
@Configuration(proxyBeanMethods = false)
class LimitProperties {

    /** Bound by the {@code @Bean} method below (not scanned), with the defaults as initial values. */
    static class Values {
        int requestsPerMinute = IngressLimits.DEFAULTS.requestsPerMinute();
        int burst = IngressLimits.DEFAULTS.burst();
        int concurrent = IngressLimits.DEFAULTS.concurrent();
        int forgeriesPerMinute = IngressLimits.DEFAULTS.forgeriesPerMinute();

        public void setRequestsPerMinute(int value) {
            requestsPerMinute = value;
        }

        public void setBurst(int value) {
            burst = value;
        }

        public void setConcurrent(int value) {
            concurrent = value;
        }

        public void setForgeriesPerMinute(int value) {
            forgeriesPerMinute = value;
        }
    }

    @Bean
    @ConfigurationProperties("aegis.limits")
    Values limitValues() {
        return new Values();
    }

    @Bean
    IngressLimits ingressLimits(Values values) {
        if (values.requestsPerMinute < 1
                || values.burst < 1
                || values.concurrent < 1
                || values.forgeriesPerMinute < 1) {
            throw new IllegalStateException("aegis.limits.* must be positive");
        }
        return new IngressLimits(values.requestsPerMinute, values.burst, values.concurrent, values.forgeriesPerMinute);
    }
}
