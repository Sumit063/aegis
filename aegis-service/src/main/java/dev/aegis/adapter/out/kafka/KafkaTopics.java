package dev.aegis.adapter.out.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The ingress log topic, created at startup because the broker never auto-creates topics. Six
 * partitions let up to six Checker instances share the work. Replication follows
 * {@code aegis.kafka.*}: one copy on a single broker, three with two in sync in the production
 * durability profile.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopics {

    private final int replicas;
    private final String minInsync;

    KafkaTopics(
            @Value("${aegis.kafka.replicas:1}") int replicas,
            @Value("${aegis.kafka.min-insync-replicas:1}") int minInsync) {
        if (replicas < 1 || minInsync < 1 || minInsync > replicas) {
            throw new IllegalStateException(
                    "aegis.kafka: replicas >= 1 and 1 <= min-insync-replicas <= replicas required");
        }
        this.replicas = replicas;
        this.minInsync = String.valueOf(minInsync);
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(6)
                .replicas(replicas)
                .config("min.insync.replicas", minInsync)
                .build();
    }

    @Bean
    NewTopic ingressTopic() {
        return topic(IngressLogPublisher.TOPIC);
    }
}
