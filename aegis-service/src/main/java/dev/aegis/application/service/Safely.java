package dev.aegis.application.service;

import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.RawMessage;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls into provider adapters that must fail closed: any exception while decrypting or verifying
 * means "not verified", and a parse failure means "unparseable", never a crash or a pass.
 */
final class Safely {

    private static final Logger log = LoggerFactory.getLogger(Safely.class);

    private Safely() {}

    /** The decrypted message, or null if it could not be decrypted. */
    static RawMessage decrypt(ProviderAdapter adapter, Channel channel, RawMessage message, Connection connection) {
        try {
            return adapter.decrypt(channel, message, connection);
        } catch (RuntimeException e) {
            log.warn(
                    "decryption failed on {}: {}",
                    connection.name(),
                    e.getClass().getSimpleName());
            return null;
        }
    }

    static boolean verify(ProviderAdapter adapter, Channel channel, RawMessage message, Connection connection) {
        try {
            return adapter.verify(channel, message, connection);
        } catch (RuntimeException e) {
            log.warn(
                    "verification error on {}: {}",
                    connection.name(),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /** The parsed message, or null if the adapter cannot read it. */
    static PaymentMessage parse(
            ProviderAdapter adapter, Channel channel, RawMessage message, Connection connection, Instant at) {
        try {
            return adapter.parse(channel, message, connection, at);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
