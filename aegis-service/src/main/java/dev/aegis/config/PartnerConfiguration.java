package dev.aegis.config;

import dev.aegis.application.service.PartnerSettings;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code aegis.partner.*}: how partner requests are judged; defaults in {@link PartnerSettings}. */
@Configuration(proxyBeanMethods = false)
class PartnerConfiguration {

    /** Where partner rates are kept: this instance's memory. */
    static final Set<String> LIMITERS = Set.of("local");

    /** Bound by the {@code @Bean} method below (not scanned), with the defaults as initial values. */
    static class Values {
        Duration freshness = PartnerSettings.DEFAULTS.freshness();
        Duration answerTimeout = PartnerSettings.DEFAULTS.answerTimeout();
        Duration retention = PartnerSettings.DEFAULTS.retention();
        String limiter = "local";

        public void setFreshness(Duration value) {
            freshness = value;
        }

        public void setAnswerTimeout(Duration value) {
            answerTimeout = value;
        }

        public void setRetention(Duration value) {
            retention = value;
        }

        public void setLimiter(String value) {
            limiter = value;
        }
    }

    @Bean
    @ConfigurationProperties("aegis.partner")
    Values partnerValues() {
        return new Values();
    }

    @Bean
    PartnerSettings partnerSettings(Values values) {
        if (!LIMITERS.contains(values.limiter)) {
            throw new IllegalStateException("aegis.partner.limiter must be one of " + LIMITERS);
        }
        try {
            return new PartnerSettings(values.freshness, values.answerTimeout, values.retention);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }
}
