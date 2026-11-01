package dev.aegis.adapter.out.crypto;

import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.config.EvidenceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the evidence cipher from the configured keys. */
@Configuration(proxyBeanMethods = false)
class CryptoConfiguration {

    @Bean
    EvidenceCipherPort evidenceCipher(EvidenceProperties properties) {
        return new AesGcmEvidenceCipher(properties.decoded(), properties.current());
    }
}
