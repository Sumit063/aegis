package dev.aegis.application.port.out;

import dev.aegis.provider.spi.Connection;
import java.util.Collection;
import java.util.Optional;

/** The configured connections, applications and partners. Read-only: they change only with a deployment. */
public interface DirectoryPort {

    Optional<Connection> connection(String name);

    Optional<Application> application(String name);

    /** The application presenting this API key (constant-time comparison), if any. */
    Optional<Application> applicationByKey(String apiKey);

    Collection<Connection> connections();

    Optional<Partner> partner(String name);
}
