package dev.aegis.provider.razorpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.shared.Money;
import dev.aegis.provider.spi.CallKind;
import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.PreparedCall;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Reply;
import dev.aegis.provider.spi.UnparseableMessageException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RazorpayAdapterTest {

    private final RazorpayAdapter adapter = new RazorpayAdapter();

    private PaymentMessage webhook(String event, String status) {
        return adapter.parse(
                Channel.SERVER,
                Razorpay.webhook(Razorpay.event(event, status, 49_900), Razorpay.WEBHOOK_SECRET, "evt_9"),
                Razorpay.connection(),
                Instant.now());
    }

    /** Strict mapping: only captured is success (CVE-2026-41432 treated "complete" as paid). */
    @ParameterizedTest
    @CsvSource({
        "payment.captured,   captured,   SUCCESS",
        "order.paid,         captured,   SUCCESS",
        "payment.failed,     failed,     FAILED",
        "payment.authorized, authorized, PENDING",
        "settlement.processed, captured, OTHER",
        "payment.dispute.created, captured, OTHER"
    })
    void mapsEventsStrictly(String event, String status, Outcome outcome) {
        assertThat(webhook(event, status).outcome()).isEqualTo(outcome);
    }

    @Test
    void readsReferencesAmountAndTimeFromAWebhook() {
        PaymentMessage message = webhook("payment.captured", "captured");

        assertThat(message.type()).isEqualTo(MessageType.WEBHOOK);
        assertThat(message.id().value()).isEqualTo("evt_9");
        assertThat(message.providerRef().value()).isEqualTo("order_X");
        assertThat(message.paymentRef().value()).isEqualTo("pay_Y");
        assertThat(message.amount()).isEqualTo(Money.of(49_900, "INR"));
        assertThat(message.sentAt()).isNotNull();
    }

    /** Each refund is its own fact: its refund ID and the refunded amount (partial refunds). */
    @Test
    void aRefundCarriesItsOwnIdentityAndAmount() {
        String body = """
                {"event":"refund.processed","created_at":1,"payload":{"refund":{"entity":
                {"id":"rfnd_1","payment_id":"pay_Y","amount":100,"currency":"INR"}}}}""";
        PaymentMessage message = adapter.parse(
                Channel.SERVER,
                Razorpay.webhook(body, Razorpay.WEBHOOK_SECRET, "evt_r"),
                Razorpay.connection(),
                Instant.now());

        assertThat(message.outcome()).isEqualTo(Outcome.REFUNDED);
        assertThat(message.paymentRef().value()).isEqualTo("pay_Y");
        assertThat(message.factRef().value()).isEqualTo("rfnd_1");
        assertThat(message.amount()).isEqualTo(Money.of(100, "INR"));
    }

    @Test
    void aRefundWithoutItsIdentityOrAmountIsUnparseable() {
        String body = """
                {"event":"refund.processed","created_at":1,"payload":{"refund":{"entity":{"payment_id":"pay_Y"}}}}""";

        assertThatThrownBy(() -> adapter.parse(
                        Channel.SERVER,
                        Razorpay.webhook(body, Razorpay.WEBHOOK_SECRET, "evt_r"),
                        Razorpay.connection(),
                        Instant.now()))
                .isInstanceOf(UnparseableMessageException.class);
    }

    // ---- required fields: fail closed (H6) -------------------------------------------------------

    private PaymentMessage parseBody(String body) {
        return adapter.parse(
                Channel.SERVER,
                Razorpay.webhook(body, Razorpay.WEBHOOK_SECRET, "evt_h6"),
                Razorpay.connection(),
                Instant.now());
    }

    private static String paymentEvent(String event, String amountAndCurrency, String createdAt) {
        return """
                {"entity":"event","event":"%s","contains":["payment"],"payload":{"payment":{"entity":\
                {"id":"pay_Y","entity":"payment",%s"status":"captured","order_id":"order_X"}}}%s}""".formatted(event, amountAndCurrency, createdAt);
    }

    /** Without a valid amount and currency the amount check could not run, so the event is refused. */
    @ParameterizedTest(name = "{0} with {1} → unparseable")
    @CsvSource(
            delimiter = '|',
            value = {
                "payment.captured   | '\"currency\":\"INR\",'",
                "payment.captured   | '\"amount\":null,\"currency\":\"INR\",'",
                "payment.captured   | '\"amount\":\"49900\",\"currency\":\"INR\",'",
                "payment.captured   | '\"amount\":499.5,\"currency\":\"INR\",'",
                "payment.captured   | '\"amount\":-1,\"currency\":\"INR\",'",
                "payment.captured   | '\"amount\":49900,'",
                "payment.captured   | '\"amount\":49900,\"currency\":null,'",
                "payment.captured   | '\"amount\":49900,\"currency\":\"XXZ\",'",
                "payment.captured   | '\"amount\":49900,\"currency\":356,'",
                "order.paid         | '\"currency\":\"INR\",'",
                "payment.failed     | '\"amount\":null,\"currency\":\"INR\",'",
                "payment.authorized | '\"amount\":49900,'"
            })
    void paymentEventsNeedAnIntegerAmountAndACurrency(String event, String fields) {
        String body =
                paymentEvent(event, fields, ",\"created_at\":" + Instant.now().getEpochSecond());

        assertThatThrownBy(() -> parseBody(body))
                .isInstanceOf(UnparseableMessageException.class)
                .hasMessageNotContaining("JSON");
    }

    @ParameterizedTest(name = "created_at {0} → unparseable")
    @CsvSource(
            delimiter = '|',
            value = {
                "''",
                "',\"created_at\":null'",
                "',\"created_at\":\"1730000000\"'",
                "',\"created_at\":0'",
                "',\"created_at\":-5'",
                "',\"created_at\":1.5'",
                "',\"created_at\":99999999999999999'"
            })
    void webhooksNeedAValidCreationTime(String createdAt) {
        String body = paymentEvent("payment.captured", "\"amount\":49900,\"currency\":\"INR\",", createdAt);

        assertThatThrownBy(() -> parseBody(body))
                .isInstanceOf(UnparseableMessageException.class)
                .hasMessageNotContaining("JSON");
    }

    @Test
    void aLegitimateEventKeepsItsExactAmountCurrencyAndTime() {
        long created = Instant.now().getEpochSecond();
        PaymentMessage message = parseBody(
                paymentEvent("order.paid", "\"amount\":49900,\"currency\":\"USD\",", ",\"created_at\":" + created));

        assertThat(message.amount()).isEqualTo(Money.of(49_900, "USD"));
        assertThat(message.sentAt()).isEqualTo(Instant.ofEpochSecond(created));
    }

    /** Unknown authenticated events need no payment fields: they are UNSUPPORTED and never forwarded. */
    @Test
    void anUnknownEventNeedsNoPaymentFieldsAndAuthorizesNothing() {
        PaymentMessage message = parseBody("""
                {"event":"payment.dispute.created","created_at":%d,"payload":{"payment":{"entity":{"id":"pay_Y"}}}}""".formatted(Instant.now().getEpochSecond()));

        assertThat(message.outcome()).isEqualTo(Outcome.OTHER);
        assertThat(message.amount()).isNull();
    }

    @ParameterizedTest(name = "refund {0} → unparseable")
    @CsvSource(
            delimiter = '|',
            value = {
                "'\"id\":\"rfnd_1\",\"payment_id\":\"pay_Y\",\"amount\":100'",
                "'\"id\":\"rfnd_1\",\"payment_id\":\"pay_Y\",\"amount\":\"100\",\"currency\":\"INR\"'",
                "'\"id\":\"rfnd_1\",\"amount\":100,\"currency\":\"INR\"'"
            })
    void aRefundNeedsItsIdentityAmountAndCurrency(String fields) {
        String body = """
                {"event":"refund.processed","created_at":%d,"payload":{"refund":{"entity":{%s}}}}""".formatted(Instant.now().getEpochSecond(), fields);

        assertThatThrownBy(() -> parseBody(body))
                .isInstanceOf(UnparseableMessageException.class)
                .hasMessageNotContaining("JSON");
    }

    private PaymentMessage statusReply(String body) {
        return ((Reply.Payment) adapter.reply(
                        CallKind.STATUS_CHECK,
                        RawMessage.of(Map.of(), "{\"payment_id\":\"pay_Y\"}"),
                        200,
                        RawMessage.of(Map.of(), body),
                        Razorpay.connection(),
                        Instant.now()))
                .message();
    }

    /** After a partial refund Razorpay still reports "captured": a capture, with the payment's amount. */
    @Test
    void aPartiallyRefundedPaymentIsStillCaptured() {
        PaymentMessage message = statusReply("""
                {"id":"pay_Y","order_id":"order_X","status":"captured","amount":49900,"currency":"INR",\
                "amount_refunded":10000,"refund_status":"partial"}""");

        assertThat(message.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(message.amount()).isEqualTo(Money.of(49_900, "INR"));
    }

    /** "refunded" is the cumulative total observed, not a refund with its own identity. */
    @Test
    void aRefundedStatusIsAnObservationOfTheTotal() {
        PaymentMessage message = statusReply("""
                {"id":"pay_Y","order_id":"order_X","status":"refunded","amount":49900,"currency":"INR",\
                "amount_refunded":49900,"refund_status":"full"}""");

        assertThat(message.outcome()).isEqualTo(Outcome.REFUNDED);
        assertThat(message.amount()).isEqualTo(Money.of(49_900, "INR"));
        assertThat(message.factRef()).isNull();
    }

    /**
     * A status reply answers Aegis's own call and is judged when it arrives. The payment's created_at
     * is when the payment was made, not when this answer was sent: a check days later stays valid.
     */
    @Test
    void aStatusReplyIsNotDatedByThePayment() {
        PaymentMessage message = statusReply("""
                {"id":"pay_Y","order_id":"order_X","status":"captured","amount":49900,"currency":"INR",\
                "created_at":1}""");

        assertThat(message.type()).isEqualTo(MessageType.STATUS_CHECK);
        assertThat(message.type().timestamped()).isFalse();
        assertThat(message.sentAt()).isNull();
    }

    @ParameterizedTest(name = "status reply {0} → unparseable")
    @CsvSource(
            delimiter = '|',
            value = {
                "'{\"id\":\"pay_Y\",\"status\":\"captured\",\"currency\":\"INR\"}'",
                "'{\"id\":\"pay_Y\",\"status\":\"captured\",\"amount\":49900}'",
                "'{\"id\":\"pay_Y\",\"status\":\"refunded\",\"amount\":49900,\"currency\":\"INR\"}'",
                "'{\"status\":\"captured\",\"amount\":49900,\"currency\":\"INR\"}'"
            })
    void statusRepliesNeedTheirFields(String body) {
        assertThatThrownBy(() -> statusReply(body))
                .isInstanceOf(UnparseableMessageException.class)
                .hasMessageNotContaining("JSON");
    }

    @Test
    void documentsLateAuthorization() {
        assertThat(adapter.lateSuccessAfterFailure()).isTrue();
        assertThat(adapter.policy().freshness()).isEqualTo(java.time.Duration.ofHours(25));
    }

    @Test
    void withoutAnEventIdTheSameBytesGetTheSameId() {
        String body = Razorpay.event("payment.captured", "captured", 49_900);
        RawMessage noId =
                RawMessage.of(Map.of("X-Razorpay-Signature", Razorpay.sign(Razorpay.WEBHOOK_SECRET, body)), body);

        PaymentMessage first = adapter.parse(Channel.SERVER, noId, Razorpay.connection(), Instant.now());
        PaymentMessage again = adapter.parse(Channel.SERVER, noId, Razorpay.connection(), Instant.now());

        assertThat(first.id()).isEqualTo(again.id());
        assertThat(first.id().value()).startsWith("body:");
    }

    @Test
    void aCallbackIsASuccessIdentifiedByItsPayment() {
        PaymentMessage message = adapter.parse(
                Channel.BROWSER,
                Razorpay.callback("order_X", "pay_Y", Razorpay.KEY_SECRET),
                Razorpay.connection(),
                Instant.now());

        assertThat(message.type()).isEqualTo(MessageType.CALLBACK);
        assertThat(message.id().value()).isEqualTo("cb:pay_Y");
        assertThat(message.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(message.amount()).isNull();
    }

    /** F8: the callback signature binds the order to the payment; another order fails. */
    @Test
    void aCallbackSignedForAnotherOrderFails() {
        RawMessage genuine = Razorpay.callback("order_SMALL", "pay_Y", Razorpay.KEY_SECRET);
        RawMessage swapped = RawMessage.of(Map.of(), genuine.text().replace("order_SMALL", "order_BIG"));

        assertThat(adapter.verify(Channel.BROWSER, genuine, Razorpay.connection()))
                .isTrue();
        assertThat(adapter.verify(Channel.BROWSER, swapped, Razorpay.connection()))
                .isFalse();
    }

    @Test
    void aFailedCheckoutFormIsUnsignedAndFails() {
        RawMessage failure =
                RawMessage.of(Map.of(), "error%5Bcode%5D=BAD_REQUEST_ERROR&error%5Bdescription%5D=Payment+failed");
        assertThat(adapter.verify(Channel.BROWSER, failure, Razorpay.connection()))
                .isFalse();
    }

    @Test
    void createsOrdersWithBasicAuthAndReadsTheReply() {
        RawMessage request = RawMessage.of(Map.of(), "{\"amount\":49900,\"currency\":\"INR\",\"receipt\":\"SHOP-1\"}");

        assertThat(adapter.facts(CallKind.VALIDATION, request).merchantRef().value())
                .isEqualTo("SHOP-1");
        PreparedCall call = adapter.prepare(CallKind.VALIDATION, request, Razorpay.connection());
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.uri()).hasToString("http://gateway.test/v1/orders");
        assertThat(call.headers().get("Authorization"))
                .isEqualTo("Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        ("rzp_test_abc:" + Razorpay.KEY_SECRET).getBytes(StandardCharsets.UTF_8)));
        assertThat(call.toString()).doesNotContain(Razorpay.KEY_SECRET);

        Reply reply = adapter.reply(
                CallKind.VALIDATION,
                request,
                200,
                RawMessage.of(
                        Map.of(), "{\"id\":\"order_X\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"created\"}"),
                Razorpay.connection(),
                Instant.now());
        assertThat(reply)
                .isEqualTo(
                        new Reply.Validated(true, dev.aegis.domain.shared.Ref.of("order_X"), Money.of(49_900, "INR")));
        assertThat(adapter.reply(
                        CallKind.VALIDATION,
                        request,
                        401,
                        RawMessage.of(Map.of(), "{}"),
                        Razorpay.connection(),
                        Instant.now()))
                .isEqualTo(new Reply.Validated(false, null, null));
    }

    @Test
    void anOrderNeedsAReceiptAndAnIntegerAmount() {
        assertThatThrownBy(() -> adapter.facts(CallKind.VALIDATION, RawMessage.of(Map.of(), "{\"amount\":499.0}")))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void checksPaymentStatus() {
        RawMessage request = RawMessage.of(Map.of(), "{\"payment_id\":\"pay_Y\"}");
        PreparedCall call = adapter.prepare(CallKind.STATUS_CHECK, request, Razorpay.connection());
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.uri()).hasToString("http://gateway.test/v1/payments/pay_Y");

        Reply reply = adapter.reply(
                CallKind.STATUS_CHECK,
                request,
                200,
                RawMessage.of(
                        Map.of(),
                        "{\"id\":\"pay_Y\",\"order_id\":\"order_X\",\"status\":\"captured\",\"amount\":49900,\"currency\":\"INR\"}"),
                Razorpay.connection(),
                Instant.now());

        PaymentMessage message = ((Reply.Payment) reply).message();
        assertThat(message.type()).isEqualTo(MessageType.STATUS_CHECK);
        assertThat(message.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(message.paymentRef().value()).isEqualTo("pay_Y");
        assertThat(adapter.supports(CallKind.PAY_REQUEST)).isFalse();
    }

    @Test
    void liveKeysOnlyInProduction() {
        adapter.validate(Razorpay.connection("rzp_test_abc"), false);
        adapter.validate(Razorpay.connection("rzp_live_abc"), true);
        assertThatThrownBy(() -> adapter.validate(Razorpay.connection("rzp_live_abc"), false))
                .hasMessageContaining("rzp_test_");
        assertThatThrownBy(() -> adapter.validate(Razorpay.connection("rzp_test_abc"), true))
                .hasMessageContaining("rzp_live_");
    }
}
