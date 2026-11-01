package dev.aegis.adapter.out.kafka;

import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * {@code kafka} health: the broker answers within 2 s. Part of readiness, because Ingress must not
 * accept traffic it cannot store (it would only answer 503).
 */
@Component("kafka")
class KafkaHealth implements HealthIndicator, DisposableBean {

    private final AdminClient admin;

    KafkaHealth(KafkaAdmin kafkaAdmin) {
        this.admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }

    @Override
    public Health health() {
        try {
            int nodes = admin.describeCluster(new DescribeClusterOptions().timeoutMs(2_000))
                    .nodes()
                    .get(3, TimeUnit.SECONDS)
                    .size();
            return Health.up().withDetail("brokers", nodes).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down().build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }

    @Override
    public void destroy() {
        admin.close();
    }
}
