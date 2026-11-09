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
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Bharat BillPay at the customer operating unit (docs/design/NPCI.md §2). The bill fetch is the
 * validation (the biller fixes the amount), {@code refId} ties fetch, payment and responses to one
 * bill, {@code txnReferenceId} identifies a payment attempt. Every message is XML signed with the
 * D33 profile: Aegis signs the application's requests, the switch signs everything it sends.
 */
public final class BbpsAdapter implements ProviderAdapter {

    static final String NS = "http://bbps.org/schema";

    @Override
    public String id() {
        return "bbps";
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
     * Only {@code BillPaymentResponse} is payment news. Its reason and code must agree: {@code
     * Successful} with {@code 000}, {@code Failure} with any other code; {@code Pending} is a deemed
     * transaction. Any other authentic message is OTHER: stored, acknowledged, never forwarded as news.
     */
    @Override
    public PaymentMessage parse(Channel channel, RawMessage message, Connection connection, Instant receivedAt) {
        if (channel != Channel.SERVER) {
            throw new UnparseableMessageException("BBPS has no browser channel");
        }
        Element root = Xml.parse(message.body()).getDocumentElement();
        if (!NS.equals(root.getNamespaceURI())) {
            throw new UnparseableMessageException("not a BBPS message");
        }
        Element head = Xml.one(root, "Head");
        Ref id = Npci.messageId(head);
        Instant sentAt = Npci.ts(head);
        Ref provider = Ref.of(connection.name());
        if (!"BillPaymentResponse".equals(root.getLocalName())) {
            return new PaymentMessage(
                    provider, MessageType.ASYNC_RESPONSE, id, null, null, Outcome.OTHER, null, sentAt, receivedAt);
        }
        Element reason = Xml.one(root, "Reason");
        Outcome outcome = outcome(Xml.attr(reason, "responseReason"), Xml.attr(reason, "responseCode"));
        return new PaymentMessage(
                provider,
                MessageType.ASYNC_RESPONSE,
                id,
                Npci.id(head, "refId"),
                Npci.id(Xml.one(root, "Txn"), "txnReferenceId"),
                outcome,
                outcome == Outcome.OTHER ? null : amount(root),
                sentAt,
                receivedAt);
    }

    @Override
    public Ack ack(Channel channel, RawMessage message) {
        return Npci.ack(message, NS, "bbps");
    }

    @Override
    public boolean supports(CallKind kind) {
        return true;
    }

    /**
     * The application sends its BBPS request unsigned. A bill fetch names its bill ({@code
     * Head@refId}); a payment its bill, attempt and amount; a status check its bill and attempt.
     */
    @Override
    public CallFacts facts(CallKind kind, RawMessage request) {
        Element root = Npci.request(request, NS, requestRoot(kind));
        Ref bill = Npci.id(Xml.one(root, "Head"), "refId");
        return switch (kind) {
            case VALIDATION -> new CallFacts(bill, bill, null, null);
            case PAY_REQUEST ->
                new CallFacts(null, bill, Npci.id(Xml.one(root, "Txn"), "txnReferenceId"), amount(root));
            case STATUS_CHECK ->
                new CallFacts(null, bill, Npci.id(Xml.one(root, "TxnStatusReq"), "txnReferenceId"), null);
        };
    }

    @Override
    public PreparedCall prepare(CallKind kind, RawMessage request, Connection connection) {
        facts(kind, request);
        return Npci.call(connection, "/bbps/" + requestRoot(kind), request);
    }

    /**
     * A reply is believed only when signed by the switch and about the request it answers (same bill,
     * same attempt). A fetch is accepted with {@code 000} and a positive amount.
     */
    @Override
    public Reply reply(
            CallKind kind, RawMessage request, int status, RawMessage reply, Connection connection, Instant at) {
        CallFacts asked = facts(kind, request);
        if (kind == CallKind.VALIDATION) {
            if (status != 200) {
                return new Reply.Validated(false, null, null);
            }
            Element root =
                    Npci.reply(reply, NS, "BillFetchResponse", connection).getDocumentElement();
            Ref bill = sameBill(root, asked.merchantRef());
            if (!"000".equals(Xml.attr(Xml.one(root, "Reason"), "responseCode"))) {
                return new Reply.Validated(false, null, null);
            }
            Money amount = Npci.minorUnits(Xml.one(root, "BillerResponse"));
            if (amount.isZero()) {
                throw new UnparseableMessageException("a bill of zero cannot be paid");
            }
            return new Reply.Validated(true, bill, amount);
        }
        if (kind == CallKind.PAY_REQUEST) {
            throw new UnsupportedOperationException(
                    "a payment request is answered by an Ack; its result arrives later");
        }
        if (status != 200) {
            throw new UnparseableMessageException("transaction status answered HTTP " + status);
        }
        Document document = Npci.reply(reply, NS, "TxnStatusResponse", connection);
        Element root = document.getDocumentElement();
        Ref bill = sameBill(root, asked.providerRef());
        Element txn = Xml.one(Xml.one(root, "TxnStatusResp"), "Txn");
        Ref attempt = Npci.id(txn, "txnReferenceId");
        if (!attempt.equals(asked.paymentRef())) {
            throw new UnparseableMessageException("status reply for another transaction");
        }
        Outcome outcome = switch (Xml.attr(txn, "status")) {
            case "SUCCESS" -> Outcome.SUCCESS;
            case "FAILURE" -> Outcome.FAILED;
            case "PENDING" -> Outcome.PENDING;
            default -> Outcome.OTHER;
        };
        return new Reply.Payment(new PaymentMessage(
                Ref.of(connection.name()),
                MessageType.STATUS_CHECK,
                Ref.of("status:" + UUID.randomUUID()),
                bill,
                attempt,
                outcome,
                outcome == Outcome.OTHER ? null : Npci.minorUnits(Xml.one(Xml.one(txn, "Amount"), "Amt")),
                null,
                at));
    }

    private static Outcome outcome(String reason, String code) {
        boolean approved = "000".equals(code);
        return switch (reason) {
            case "Successful" -> {
                if (!approved) {
                    throw new UnparseableMessageException("Successful with response code " + code);
                }
                yield Outcome.SUCCESS;
            }
            case "Failure" -> {
                if (approved) {
                    throw new UnparseableMessageException("Failure with the approval code 000");
                }
                yield Outcome.FAILED;
            }
            case "Pending" -> Outcome.PENDING;
            default -> Outcome.OTHER;
        };
    }

    private static Money amount(Element root) {
        return Npci.minorUnits(Xml.one(Xml.one(root, "Amount"), "Amt"));
    }

    private static Ref sameBill(Element root, Ref asked) {
        Ref bill = Npci.id(Xml.one(root, "Head"), "refId");
        if (!bill.equals(asked)) {
            throw new UnparseableMessageException("reply for another bill");
        }
        return bill;
    }

    private static String requestRoot(CallKind kind) {
        return switch (kind) {
            case VALIDATION -> "BillFetchRequest";
            case PAY_REQUEST -> "BillPaymentRequest";
            case STATUS_CHECK -> "TxnStatusRequest";
        };
    }
}
