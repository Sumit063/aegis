package dev.aegis.config;

import dev.aegis.application.port.out.DirectoryPort;
import dev.aegis.provider.spi.ProviderRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Installs the provider adapters and checks every connection against them before anything starts. */
@Configuration(proxyBeanMethods = false)
class ProviderConfiguration {

    @Bean
    ProviderRegistry providerRegistry(DirectoryPort directory, AegisProperties properties) {
        ProviderRegistry registry = ProviderRegistry.installed();
        registry.validate(directory.connections(), properties.environment().isProduction());
        return registry;
    }
}
