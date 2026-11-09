package dev.aegis.provider.npci;

import dev.aegis.domain.check.Check;
import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import dev.aegis.provider.spi.Ack;
import dev.aegis.provider.spi.CallFacts;
import dev.aegis.provider.spi.CallKind;
import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.PreparedCall;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Reply;
import dev.aegis.provider.spi.UnparseableMessageException;
import dev.aegis.provider.spi.Xml;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.w3c.dom.Element;

/**
 * UPI at the payer's PSP (docs/design/NPCI.md §3). {@code ReqValAdd} validates the payee and records
 * the amount the payer instructs (D34); {@code ReqPay} goes out once; {@code RespPay} reports the
 * result, a deemed transaction ({@code DEEMED}, PENDING) or a reversal of it; {@code ReqChkTxn} asks.
 * {@code Txn@refId} links a payment to its validation; {@code Txn@id} identifies a transaction.
 */
public final class UpiAdapter implements ProviderAdapter {

    static final String NS = "http://npci.org/upi/schema/";

    @Override
    public String id() {
        return "upi";
    }

    @Override
    public Set<String> requiredSecrets() {
        return Set.of(Npci.SIGNING_KEY, Npci.SWITCH_CERTIFICATE);
    }

    @Override
    public void validate(Connection connection, boolean production) {
        Npci.validate(connection);
    }

    @Override
    public Duration freshness() {
        return Npci.FRESHNESS;
    }

    @Override
    public Check.Limits enquiryLimits() {
        return Npci.LIMITS;
    }

    @Override
    public boolean verify(Channel channel, RawMessage message, Connection connection) {
        return channel == Channel.SERVER
                && Npci.verified(message, NS, connection).isPresent();
    }

    /**
     * Only {@code RespPay} is payment news. {@code PAY}: {@code SUCCESS}, {@code FAILURE}, {@code
     * PENDING}/{@code DEEMED}. {@code REVERSAL}: {@code SUCCESS} returns the money of the payment named
     * by {@code orgTxnId} (REVERSED, its own fact); a reversal that did not happen is not news.
     */
    @Override
    public PaymentMessage parse(Channel channel, RawMessage message, Connection connection, Instant receivedAt) {
        if (channel != Channel.SERVER) {
            throw new UnparseableMessageException("UPI has no browser channel");
        }
        Element root = Xml.parse(message.body()).getDocumentElement();
        if (!NS.equals(root.getNamespaceURI())) {
            throw new UnparseableMessageException("not a UPI message");
        }
        Element head = Xml.one(root, "Head");
        Ref id = Npci.messageId(head);
        Instant sentAt = Npci.ts(head);
        Ref provider = Ref.of(connection.name());
        if (!"RespPay".equals(root.getLocalName())) {
            return new PaymentMessage(
                    provider, MessageType.ASYNC_RESPONSE, id, null, null, Outcome.OTHER, null, sentAt, receivedAt);
        }
        Element txn = Xml.one(root, "Txn");
        Element resp = Xml.one(root, "Resp");
        String result = Xml.attr(resp, "result");
        boolean reversal = switch (Xml.attr(txn, "type")) {
            case "PAY" -> false;
            case "REVERSAL" -> true;
            default -> throw new UnparseableMessageException("RespPay of an unknown transaction type");
        };
        Outcome outcome = reversal ? ("SUCCESS".equals(result) ? Outcome.REVERSED : Outcome.OTHER) : outcome(result);
        return new PaymentMessage(
                provider,
                MessageType.ASYNC_RESPONSE,
                id,
                Npci.id(txn, "refId"),
                Npci.id(txn, reversal ? "orgTxnId" : "id"),
                outcome,
                outcome == Outcome.OTHER ? null : settled(resp),
                sentAt,
                receivedAt,
                reversal ? Npci.id(txn, "id") : null);
    }

    @Override
    public Ack ack(Channel channel, RawMessage message) {
        return Npci.ack(message, NS, "upi");
    }

    @Override
    public boolean supports(CallKind kind) {
        return true;
    }

    /**
     * {@code ReqValAdd}: the application's order ({@code Txn@refId}) and the amount the payer
     * instructs ({@code Payee/Amount}). {@code ReqPay}: the validation ({@code Txn@refId}), the
     * transaction ({@code Txn@id}) and {@code Payer/Amount}. {@code ReqChkTxn}: {@code orgTxnId}.
     */
    @Override
    public CallFacts facts(CallKind kind, RawMessage request) {
        Element root = Npci.request(request, NS, requestRoot(kind));
        Element txn = Xml.one(root, "Txn");
        return switch (kind) {
            case VALIDATION -> {
                Npci.id(txn, "id");
                Money amount = amount(Xml.one(Xml.one(root, "Payee"), "Amount"));
                if (amount.isZero()) {
                    throw new UnparseableMessageException("a payment of zero cannot be validated");
                }
                yield new CallFacts(Npci.id(txn, "refId"), null, null, amount);
            }
            case PAY_REQUEST -> {
                if (!"PAY".equals(Xml.attr(txn, "type"))) {
                    throw new UnparseableMessageException("ReqPay must be of type PAY");
                }
                yield new CallFacts(
                        null,
                        Npci.id(txn, "refId"),
                        Npci.id(txn, "id"),
                        amount(Xml.one(Xml.one(root, "Payer"), "Amount")));
            }
            case STATUS_CHECK -> new CallFacts(null, Npci.id(txn, "refId"), Npci.id(txn, "orgTxnId"), null);
        };
    }

    @Override
    public PreparedCall prepare(CallKind kind, RawMessage request, Connection connection) {
        facts(kind, request);
        return Npci.call(connection, "/upi/" + requestRoot(kind), request);
    }

    /**
     * A reply is believed only when signed by the switch and about the transaction asked about. The
     * validation's amount is the one the application stated (D34): the switch confirms the payee.
     */
    @Override
    public Reply reply(
            CallKind kind, RawMessage request, int status, RawMessage reply, Connection connection, Instant at) {
        CallFacts asked = facts(kind, request);
        if (kind == CallKind.VALIDATION) {
            if (status != 200) {
                return new Reply.Validated(false, null, null);
            }
            Element root = Npci.reply(reply, NS, "RespValAdd", connection).getDocumentElement();
            Ref validation = Npci.id(Xml.one(root, "Txn"), "id");
            Ref requested = Npci.id(Xml.one(Npci.request(request, NS, "ReqValAdd"), "Txn"), "id");
            if (!validation.equals(requested)) {
                throw new UnparseableMessageException("reply for another validation");
            }
            boolean accepted = "SUCCESS".equals(Xml.attr(Xml.one(root, "Resp"), "result"));
            return accepted
                    ? new Reply.Validated(true, validation, asked.amount())
                    : new Reply.Validated(false, null, null);
        }
        if (kind == CallKind.PAY_REQUEST) {
            throw new UnsupportedOperationException(
                    "a payment request is answered by an Ack; its result arrives later");
        }
        if (status != 200) {
            throw new UnparseableMessageException("transaction status answered HTTP " + status);
        }
        Element root = Npci.reply(reply, NS, "RespChkTxn", connection).getDocumentElement();
        Element txn = Xml.one(root, "Txn");
        Ref payment = Npci.id(txn, "orgTxnId");
        Ref validation = Npci.id(txn, "refId");
        if (!payment.equals(asked.paymentRef()) || !validation.equals(asked.providerRef())) {
            throw new UnparseableMessageException("status reply for another transaction");
        }
        Element resp = Xml.one(root, "Resp");
        String result = Xml.attr(resp, "result");
        Outcome outcome = "REVERSAL".equals(Xml.attr(txn, "type"))
                ? ("SUCCESS".equals(result) ? Outcome.REVERSED : Outcome.OTHER)
                : outcome(result);
        return new Reply.Payment(new PaymentMessage(
                Ref.of(connection.name()),
                MessageType.STATUS_CHECK,
                Ref.of("status:" + UUID.randomUUID()),
                validation,
                payment,
                outcome,
                outcome == Outcome.OTHER
                        ? null
                        : Npci.majorUnits(Xml.attr(resp, "settAmount"), Xml.attr(resp, "settCurrency")),
                null,
                at));
    }

    private static Outcome outcome(String result) {
        return switch (result) {
            case "SUCCESS" -> Outcome.SUCCESS;
            case "FAILURE" -> Outcome.FAILED;
            case "PENDING", "DEEMED" -> Outcome.PENDING;
            default -> Outcome.OTHER;
        };
    }

    /** The amount credited to the payee, as the switch settled it. */
    private static Money settled(Element resp) {
        Element payee = Xml.children(resp, "Ref").stream()
                .filter(ref -> "PAYEE".equals(ref.getAttribute("type")))
                .reduce((a, b) -> {
                    throw new UnparseableMessageException("Resp has more than one PAYEE Ref");
                })
                .orElseThrow(() -> new UnparseableMessageException("Resp without a PAYEE Ref"));
        return Npci.majorUnits(Xml.attr(payee, "settAmount"), Xml.attr(payee, "settCurrency"));
    }

    private static Money amount(Element amount) {
        return Npci.majorUnits(Xml.attr(amount, "value"), Xml.attr(amount, "curr"));
    }

    private static String requestRoot(CallKind kind) {
        return switch (kind) {
            case VALIDATION -> "ReqValAdd";
            case PAY_REQUEST -> "ReqPay";
            case STATUS_CHECK -> "ReqChkTxn";
        };
    }
}
