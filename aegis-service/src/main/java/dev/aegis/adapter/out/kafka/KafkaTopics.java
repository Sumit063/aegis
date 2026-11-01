package dev.aegis.adapter.out.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The ingress log topic, created at startup because the broker never auto-creates topics. Six
 * partitions let up to six Checker instances share the work; one replica matches the single broker.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopics {

    @Bean
    NewTopic ingressTopic() {
        return TopicBuilder.name(IngressLogPublisher.TOPIC)
                .partitions(6)
                .replicas(1)
                .build();
    }
}
