package dev.aegis.config;

import dev.aegis.application.port.out.DirectoryPort;
import dev.aegis.provider.spi.Destinations;
import dev.aegis.provider.spi.ProviderRegistry;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Installs the provider adapters and checks every connection against them before anything starts. */
@Configuration(proxyBeanMethods = false)
class ProviderConfiguration {

    @Bean
    ProviderRegistry providerRegistry(
            DirectoryPort directory,
            AegisProperties properties,
            @Value("${aegis.network.allow-plain-http:true}") boolean allowPlainHttp) {
        ProviderRegistry registry = ProviderRegistry.installed();
        registry.validate(directory.connections(), properties.environment().isProduction());
        checkDestinations(directory, properties, allowPlainHttp);
        return registry;
    }

    /** Every configured destination passes {@link Destinations} before anything starts (SSRF). */
    private static void checkDestinations(DirectoryPort directory, AegisProperties properties, boolean allowPlainHttp) {
        directory.connections().stream()
                .filter(c -> c.baseUrl() != null)
                .forEach(c -> check("aegis.connections." + c.name() + ".base-url", c.baseUrl(), allowPlainHttp));
        properties.applications().forEach((name, app) -> {
            check("aegis.applications." + name + ".forward-url", app.forwardUrl(), allowPlainHttp);
            if (app.returnUrl() != null) {
                check("aegis.applications." + name + ".return-url", app.returnUrl(), allowPlainHttp);
            }
            if (app.partnerUrl() != null) {
                check("aegis.applications." + name + ".partner-url", app.partnerUrl(), allowPlainHttp);
            }
        });
    }

    private static void check(String setting, URI uri, boolean allowPlainHttp) {
        try {
            Destinations.check(uri, allowPlainHttp);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(setting + ": " + e.getMessage());
        }
    }
}
