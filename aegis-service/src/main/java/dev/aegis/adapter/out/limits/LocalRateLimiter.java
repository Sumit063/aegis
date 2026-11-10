package dev.aegis.adapter.out.limits;

import dev.aegis.application.port.out.RateLimiterPort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Partner rates in this instance's memory ({@code aegis.partner.limiter: local}, the default): exact
 * for one instance, N times the configured rate across N instances. Keys are configured partners and
 * operations, so the map stays small. Time is {@link System#nanoTime()}: it never steps back.
 */
@Component
@ConditionalOnProperty(name = "aegis.partner.limiter", havingValue = "local", matchIfMissing = true)
class LocalRateLimiter implements RateLimiterPort {

    private final Map<String, Long> tats = new ConcurrentHashMap<>();
    private final LongSupplier nanos;

    @Autowired
    LocalRateLimiter() {
        this(System::nanoTime);
    }

    LocalRateLimiter(LongSupplier nanos) {
        this.nanos = nanos;
    }

    @Override
    public Decision acquire(String key, Rate rate) {
        long now = nanos.getAsLong();
        Decision[] decision = new Decision[1];
        tats.compute(key, (k, stored) -> {
            Gcra.Step step = Gcra.step(stored, now, rate);
            decision[0] = step.decision();
            return step.tat();
        });
        return decision[0];
    }
}
