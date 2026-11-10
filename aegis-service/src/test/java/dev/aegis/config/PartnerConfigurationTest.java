package dev.aegis.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.application.service.PartnerSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PartnerConfigurationTest {

    final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Binding.class, PartnerConfiguration.class);

    /** Binding is on in the application (Boot); a bare context needs it switched on. */
    @EnableConfigurationProperties
    static class Binding {}

    @Test
    void defaultsApply() {
        runner.run(context -> assertThat(context.getBean(PartnerSettings.class)).isEqualTo(PartnerSettings.DEFAULTS));
    }

    @Test
    void settingsBind() {
        runner.withPropertyValues(
                        "aegis.partner.freshness=2m", "aegis.partner.answer-timeout=20s", "aegis.partner.retention=7d")
                .run(context -> assertThat(context.getBean(PartnerSettings.class))
                        .isEqualTo(new PartnerSettings(
                                Duration.ofMinutes(2), Duration.ofSeconds(20), Duration.ofDays(7))));
    }

    @Test
    void anUnknownLimiterOrARedisWithoutAddressStopsStartup() {
        runner.withPropertyValues("aegis.partner.limiter=memcached")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("aegis.partner.limiter must be one of"));
        runner.withPropertyValues("aegis.partner.limiter=redis", "aegis.partner.redis-url= ")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessage("aegis.partner.redis-url must be set when aegis.partner.limiter is redis"));
    }

    @Test
    void retentionShorterThanTwiceTheFreshnessOrAShortAnswerTimeoutStopsStartup() {
        runner.withPropertyValues("aegis.partner.freshness=1h", "aegis.partner.retention=1h")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("retention must be at least twice the freshness"));
        runner.withPropertyValues("aegis.partner.answer-timeout=5s")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("answer-timeout at least 15s"));
    }
}
