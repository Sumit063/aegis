package dev.aegis.adapter.out.kafka;

import dev.aegis.application.port.out.IngressLogPort;
import dev.aegis.application.port.out.IngressRecord;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes ingress records to Kafka and waits for the broker's acknowledgement ({@code acks=all},
 * idempotent producer). Blocking is cheap on a virtual thread, and it is what makes "acknowledged
 * means stored" true.
 */
@Component
class IngressLogPublisher implements IngressLogPort {

    static final String TOPIC = "aegis.inbound";
    private static final long TIMEOUT_SECONDS = 3;

    private final KafkaTemplate<String, byte[]> kafka;
    private final JsonMapper json;

    IngressLogPublisher(KafkaTemplate<String, byte[]> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    @Override
    public void append(IngressRecord record) {
        try {
            kafka.send(TOPIC, record.key(), json.writeValueAsBytes(record)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while writing to Kafka", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not acknowledge the record", e);
        }
    }
}
