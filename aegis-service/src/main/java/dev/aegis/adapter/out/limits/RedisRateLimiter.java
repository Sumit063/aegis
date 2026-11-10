package dev.aegis.adapter.out.limits;

import dev.aegis.application.port.out.RateLimiterPort;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Partner rates in Redis ({@code aegis.partner.limiter: redis}, DECISIONS D36): one allowance per key
 * for every Aegis instance. Each decision is one atomic script ({@code gcra.lua}) on Redis's own
 * clock. Fails closed and fast: a command that does not complete within the timeout, or Redis being
 * unreachable, is {@link IllegalStateException} and the request is refused. The connection is opened
 * on first use and re-established by the client, so Aegis starts, and recovers, without Redis.
 */
@Component
@ConditionalOnProperty(name = "aegis.partner.limiter", havingValue = "redis")
class RedisRateLimiter implements RateLimiterPort {

    private final RedisClient client;
    private final String prefix;
    private final String script;
    private final String sha;
    private volatile StatefulRedisConnection<String, String> connection;

    RedisRateLimiter(
            @Value("${aegis.partner.redis-url}") String url,
            @Value("${aegis.partner.redis-timeout:250ms}") Duration timeout,
            @Value("${aegis.environment}") String environment) {
        RedisURI uri = RedisURI.create(url);
        uri.setTimeout(timeout);
        client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                .build());
        // Environments may share a Redis; their allowances never mix.
        prefix = "aegis:" + environment.toLowerCase(Locale.ROOT) + ":rate:";
        script = load();
        sha = sha1(script);
    }

    @Override
    public Decision acquire(String key, Rate rate) {
        String[] keys = {prefix + key};
        String interval = String.valueOf(60_000_000L / rate.perMinute());
        String burst = String.valueOf(rate.burst());
        List<Long> result;
        try {
            RedisCommands<String, String> redis = connection().sync();
            try {
                result = redis.evalsha(sha, ScriptOutputType.MULTI, keys, interval, burst);
            } catch (RedisNoScriptException e) {
                result = redis.eval(script, ScriptOutputType.MULTI, keys, interval, burst);
            }
        } catch (RedisException e) {
            throw new IllegalStateException("redis: " + e.getClass().getSimpleName(), e);
        }
        return result.get(0) == 1L ? Decision.ALLOWED : new Decision(false, Duration.ofNanos(result.get(1) * 1000));
    }

    private StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> current = connection;
        if (current == null) {
            synchronized (this) {
                current = connection;
                if (current == null) {
                    current = client.connect();
                    connection = current;
                }
            }
        }
        return current;
    }

    @PreDestroy
    void close() {
        if (connection != null) {
            connection.close();
        }
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }

    private static String load() {
        try (InputStream in = RedisRateLimiter.class.getResourceAsStream("gcra.lua")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Redis names a loaded script by its SHA-1: EVALSHA sends 40 characters instead of the script.
    private static String sha1(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
