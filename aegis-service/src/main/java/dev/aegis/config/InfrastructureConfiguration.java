package dev.aegis.config;

import dev.aegis.adapter.out.crypto.AesGcmEvidenceCipher;
import dev.aegis.application.port.out.EvidenceCipherPort;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires infrastructure that needs configuration values: the clock and the evidence cipher. */
@Configuration(proxyBeanMethods = false)
class InfrastructureConfiguration {

    /** One clock for every timestamp, so tests can fix time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EvidenceCipherPort evidenceCipher(EvidenceProperties properties) {
        return new AesGcmEvidenceCipher(properties.decoded(), properties.current());
    }
}
