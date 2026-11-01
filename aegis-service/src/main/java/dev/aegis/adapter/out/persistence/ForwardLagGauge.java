package dev.aegis.adapter.out.persistence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code aegis_forward_lag_seconds}: how long the oldest due message has been waiting for its
 * application (0 when nothing is due). Read on each scrape with one indexed query.
 */
@Component
class ForwardLagGauge implements MeterBinder {

    private static final String OLDEST_DUE = """
            SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(forward_due_at)), 0)
            FROM payments WHERE forward_due_at <= now()""";

    private final JdbcTemplate jdbc;

    ForwardLagGauge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("aegis.forward.lag", this, ForwardLagGauge::seconds)
                .description("age of the oldest message waiting to be delivered")
                .baseUnit("seconds")
                .register(registry);
    }

    private double seconds() {
        try {
            Double value = jdbc.queryForObject(OLDEST_DUE, Double.class);
            return value == null ? 0 : value;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
