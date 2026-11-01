package dev.aegis.adapter.out.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.aegis.adapter.out.persistence.entity.ValidationEntity;
import dev.aegis.adapter.out.persistence.repository.ValidationJpaRepository;
import dev.aegis.application.port.out.Evidence;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.payment.Validation;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@link ValidationRepository} on PostgreSQL; {@code data} holds the request and the reply. */
@Component
class ValidationPersistenceAdapter implements ValidationRepository {

    /** {@code validations.data} (LLD §7). Bodies are ciphertext. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Data(Request request, Response response) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(Instant at, Map<String, String> headers, String body) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Response(Instant at, int httpStatus, String body) {}

    private final ValidationJpaRepository rows;
    private final JsonMapper json;
    private final Clock clock;

    ValidationPersistenceAdapter(ValidationJpaRepository rows, JsonMapper json, Clock clock) {
        this.rows = rows;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Validation insert(Validation validation, Evidence exchange, Instant requestedAt, Instant answeredAt) {
        Data data = new Data(
                new Request(requestedAt, exchange.headers(), exchange.body()),
                new Response(answeredAt, exchange.httpStatus(), exchange.reply()));
        Money amount = validation.amount();
        ValidationEntity row = new ValidationEntity(
                validation.provider().value(),
                validation.merchantRef().value(),
                validation.providerRef() == null
                        ? null
                        : validation.providerRef().value(),
                amount == null ? null : amount.minorUnits(),
                amount == null ? null : amount.currency().getCurrencyCode(),
                validation.status().name(),
                json.writeValueAsString(data),
                clock.instant());
        return toDomain(rows.saveAndFlush(row));
    }

    @Override
    public Optional<Validation> lock(Ref provider, Ref providerRef) {
        return rows.findByProviderAndProviderRef(provider.value(), providerRef.value())
                .map(ValidationPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Validation> find(long id) {
        return rows.findById(id).map(ValidationPersistenceAdapter::toDomain);
    }

    @Override
    public void update(Validation validation) {
        ValidationEntity row = rows.findById(validation.id())
                .orElseThrow(() -> new IllegalStateException("no validation " + validation.id()));
        if (!row.getStatus().equals(validation.status().name())) {
            row.changeStatus(validation.status().name(), clock.instant());
        }
    }

    static Validation toDomain(ValidationEntity row) {
        return new Validation(
                row.getId(),
                Ref.of(row.getProvider()),
                Ref.of(row.getMerchantRef()),
                row.getProviderRef() == null ? null : Ref.of(row.getProviderRef()),
                row.getAmount() == null ? null : Money.of(row.getAmount(), row.getCurrency()),
                Validation.Status.valueOf(row.getStatus()));
    }
}
