package dev.aegis.adapter.out.persistence;

import dev.aegis.adapter.out.persistence.entity.PaymentEntity;
import dev.aegis.application.port.out.Evidence;
import dev.aegis.domain.message.BlockReason;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.Verdict;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Maps {@code payments} rows to the domain {@link Payment} and back (Data Mapper). */
@Component
class PaymentRows {

    private static final TypeReference<List<StoredEntry>> ENTRIES = new TypeReference<>() {};

    private final JsonMapper json;

    PaymentRows(JsonMapper json) {
        this.json = json;
    }

    Payment toDomain(PaymentEntity row) {
        List<Payment.Entry> entries =
                read(row.getMessages()).stream().map(PaymentRows::entry).toList();
        return new Payment(
                row.getId(),
                row.getValidationId(),
                Ref.of(row.getProvider()),
                row.getPaymentRef() == null ? null : Ref.of(row.getPaymentRef()),
                status(row.getStatus()),
                entries);
    }

    List<StoredEntry> read(String messages) {
        return json.readValue(messages, ENTRIES);
    }

    String write(List<StoredEntry> entries) {
        return json.writeValueAsString(entries);
    }

    /**
     * The row's new message list: entries already stored keep their evidence and delivery history,
     * taking only the counters the domain changes; new entries take their evidence.
     */
    List<StoredEntry> merge(List<StoredEntry> stored, Payment payment, Map<Ref, Evidence> evidence) {
        Map<Integer, StoredEntry> byNumber =
                stored.stream().collect(Collectors.toMap(StoredEntry::n, Function.identity()));
        List<StoredEntry> merged = new ArrayList<>();
        for (Payment.Entry entry : payment.entries()) {
            StoredEntry existing = byNumber.get(entry.n());
            merged.add(
                    existing != null
                            ? existing.with(entry.duplicates(), forward(existing.forward(), entry.forward()))
                            : stored(entry, evidence.get(entry.messageId())));
        }
        return merged;
    }

    static boolean awaitsForwarding(Payment payment) {
        return payment.entries().stream().anyMatch(e -> e.forward() == Payment.Forward.PENDING);
    }

    private static StoredEntry.Forward forward(StoredEntry.Forward existing, Payment.Forward state) {
        return existing == null ? StoredEntry.Forward.of(state.name()) : existing.withStatus(state.name());
    }

    private static StoredEntry stored(Payment.Entry entry, Evidence evidence) {
        Evidence kept = evidence == null ? Evidence.of(Map.of(), null) : evidence;
        return new StoredEntry(
                entry.n(),
                entry.type().name(),
                entry.messageId().value(),
                entry.at(),
                entry.outcome().name(),
                entry.verdict().passed() ? "PASSED" : "BLOCKED",
                entry.verdict().passed() ? null : entry.verdict().reason().name(),
                entry.duplicates(),
                kept.headers().isEmpty() ? null : kept.headers(),
                kept.body(),
                kept.reply(),
                kept.httpStatus() == 0 ? null : kept.httpStatus(),
                StoredEntry.Forward.of(entry.forward().name()),
                entry.amount() == null ? null : entry.amount().minorUnits(),
                entry.amount() == null ? null : entry.amount().currency().getCurrencyCode(),
                entry.factRef() == null ? null : entry.factRef().value());
    }

    private static Payment.Entry entry(StoredEntry stored) {
        Verdict verdict =
                stored.reason() == null ? Verdict.PASSED : Verdict.blocked(BlockReason.valueOf(stored.reason()));
        Payment.Forward forward = stored.forward() == null
                ? Payment.Forward.NONE
                : Payment.Forward.valueOf(stored.forward().status());
        return new Payment.Entry(
                stored.n(),
                MessageType.valueOf(stored.type()),
                Ref.of(stored.messageId()),
                stored.at(),
                Outcome.valueOf(stored.outcome()),
                verdict,
                stored.duplicates(),
                forward,
                stored.amount() == null ? null : Money.of(stored.amount(), stored.currency()),
                stored.factRef() == null ? null : Ref.of(stored.factRef()));
    }

    // REFUNDED was a payment state before V3; a refund is a fact on a captured payment, so a row
    // written by an older version (e.g. during a rollback) reads as captured.
    private static Outcome status(String stored) {
        if (stored == null) {
            return null;
        }
        return stored.equals("REFUNDED") ? Outcome.SUCCESS : Outcome.valueOf(stored);
    }
}
