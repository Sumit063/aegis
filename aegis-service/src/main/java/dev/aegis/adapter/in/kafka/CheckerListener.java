package dev.aegis.adapter.in.kafka;

import dev.aegis.application.port.in.CheckMessageUseCase;
import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.config.ConditionalOnRole;
import dev.aegis.config.Role;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes the ingress log. The offset is committed only after {@link CheckMessageUseCase#check}
 * returns, i.e. after the database commit; a redelivery is recognised as a repeat (LLD T14).
 */
@Component
@ConditionalOnRole(Role.CHECKER)
class CheckerListener {

    private final CheckMessageUseCase checker;
    private final JsonMapper json;

    CheckerListener(CheckMessageUseCase checker, JsonMapper json) {
        this.checker = checker;
        this.json = json;
    }

    @KafkaListener(topics = "aegis.inbound", groupId = "aegis-checker")
    void onRecord(byte[] value) {
        IngressRecord record = json.readValue(value, IngressRecord.class);
        MDC.put("correlationId", record.correlationId() == null ? record.id().toString() : record.correlationId());
        try {
            checker.check(record);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
