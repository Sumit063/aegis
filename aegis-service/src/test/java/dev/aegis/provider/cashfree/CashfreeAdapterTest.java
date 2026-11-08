package dev.aegis.provider.cashfree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.domain.message.MessageType;
import dev.aegis.domain.message.Outcome;
import dev.aegis.domain.message.PaymentMessage;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import dev.aegis.provider.spi.CallKind;
import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.PreparedCall;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Reply;
import dev.aegis.provider.spi.UnparseableMessageException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CashfreeAdapterTest {

    private final CashfreeAdapter adapter = new CashfreeAdapter();

    private PaymentMessage parse(String body) {
        return adapter.parse(Channel.SERVER, Cashfree.webhook(body), Cashfree.connection(), Instant.now());
    }

    // ---- signature ---------------------------------------------------------------------------------

    /** Cashfree's documented construction: base64(HMAC-SHA256(timestamp + raw body)), no separator. */
    @Test
    void theTimestampIsSignedWithTheBody() {
        String body = Cashfree.payment("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS", "2.00");
        String signature = Cashfree.sign(Cashfree.CLIENT_SECRET, "1746427759733", body);
        RawMessage genuine =
                RawMessage.of(Map.of("x-webhook-signature", signature, "x-webhook-timestamp", "1746427759733"), body);
        RawMessage otherTime =
                RawMessage.of(Map.of("x-webhook-signature", signature, "x-webhook-timestamp", "1746427759734"), body);
        RawMessage withDot = RawMessage.of(
                Map.of(
                        "x-webhook-signature",
                        Cashfree.sign(Cashfree.CLIENT_SECRET, "1746427759733.", body),
                        "x-webhook-timestamp",
                        "1746427759733"),
                body);

        assertThat(adapter.verify(Channel.SERVER, genuine, Cashfree.connection()))
                .isTrue();
        assertThat(adapter.verify(Channel.SERVER, otherTime, Cashfree.connection()))
                .isFalse();
        assertThat(adapter.verify(Channel.SERVER, withDot, Cashfree.connection()))
                .isFalse();
    }

    @ParameterizedTest(name = "timestamp {0} → not verified")
    @CsvSource(
            delimiter = '|',
            value = {"''", "'soon'", "'-1'", "'1746427759733.5'", "'1234567890123456'"})
    void aMissingOrMalformedTimestampNeverVerifies(String timestamp) {
        String body = Cashfree.payment("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS", "2.00");
        RawMessage message = RawMessage.of(
                Map.of(
                        "x-webhook-signature",
                        Cashfree.sign(Cashfree.CLIENT_SECRET, timestamp, body),
                        "x-webhook-timestamp",
                        timestamp),
                body);

        assertThat(adapter.verify(Channel.SERVER, message, Cashfree.connection()))
                .isFalse();
    }

    @Test
    void theBrowserChannelIsNeverTrusted() {
        RawMessage redirect = RawMessage.of(Map.of(), "order_id=order_1");

        assertThat(adapter.verify(Channel.BROWSER, redirect, Cashfree.connection()))
                .isFalse();
        assertThatThrownBy(() -> adapter.parse(Channel.BROWSER, redirect, Cashfree.connection(), Instant.now()))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void identityAndTimeComeFromTheSignedHeaders() {
        RawMessage message = Cashfree.webhook(Cashfree.payment("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS", "2.00"));
        long millis = Long.parseLong(message.header("x-webhook-timestamp").orElseThrow());

        PaymentMessage parsed = adapter.parse(Channel.SERVER, message, Cashfree.connection(), Instant.now());

        assertThat(parsed.id()).isEqualTo(Ref.of("idem:idem-1"));
        assertThat(parsed.sentAt()).isEqualTo(Instant.ofEpochMilli(millis));
        assertThat(parsed.type()).isEqualTo(MessageType.WEBHOOK);
        assertThat(adapter.policy().lateSuccessAfterFailure()).isTrue();
    }

    @Test
    void withoutAnIdempotencyKeyTheSameBytesGetTheSameId() {
        String body = Cashfree.payment("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS", "2.00");
        RawMessage first = Cashfree.webhook(body, Cashfree.CLIENT_SECRET, " ");
        RawMessage again = Cashfree.webhook(body, Cashfree.CLIENT_SECRET, " ");

        Ref id = adapter.parse(Channel.SERVER, first, Cashfree.connection(), Instant.now())
                .id();

        assertThat(id.value()).startsWith("body:");
        assertThat(adapter.parse(Channel.SERVER, again, Cashfree.connection(), Instant.now())
                        .id())
                .isEqualTo(id);
    }

    // ---- mapping -----------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} → {2}")
    @CsvSource({
        "PAYMENT_SUCCESS_WEBHOOK,      SUCCESS,      SUCCESS",
        "PAYMENT_FAILED_WEBHOOK,       FAILED,       FAILED",
        "PAYMENT_USER_DROPPED_WEBHOOK, USER_DROPPED, FAILED",
        "PAYMENT_CHARGES_WEBHOOK,      SUCCESS,      OTHER",
        "DISPUTE_CREATED,              SUCCESS,      OTHER",
        "SETTLEMENT_WEBHOOK,           SUCCESS,      OTHER"
    })
    void mapsWebhooksStrictly(String type, String status, Outcome outcome) {
        PaymentMessage message = parse(Cashfree.payment(type, status, "1.80"));

        assertThat(message.outcome()).isEqualTo(outcome);
        assertThat(message.providerRef()).isEqualTo(Ref.of("order_1"));
        assertThat(message.paymentRef()).isEqualTo(Ref.of("5114910"));
        assertThat(message.amount()).isEqualTo(outcome == Outcome.OTHER ? null : Money.of(180, "INR"));
    }

    /** A success webhook saying FAILED (or the reverse) is contradictory: refused, never guessed. */
    @ParameterizedTest(name = "{0} with {1} → unparseable")
    @CsvSource({
        "PAYMENT_SUCCESS_WEBHOOK,      FAILED",
        "PAYMENT_SUCCESS_WEBHOOK,      PENDING",
        "PAYMENT_FAILED_WEBHOOK,       SUCCESS",
        "PAYMENT_USER_DROPPED_WEBHOOK, SUCCESS"
    })
    void aStatusThatContradictsTheTypeIsRefused(String type, String status) {
        assertThatThrownBy(() -> parse(Cashfree.payment(type, status, "1.80")))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @ParameterizedTest(name = "refund {0} → {1}")
    @CsvSource({"SUCCESS, REFUNDED", "PENDING, OTHER", "CANCELLED, OTHER", "ONHOLD, OTHER"})
    void aRefundIsAFactOnlyOnceSuccessful(String status, Outcome outcome) {
        PaymentMessage message = parse(Cashfree.refund(status, "2.00"));

        assertThat(message.outcome()).isEqualTo(outcome);
        assertThat(message.providerRef()).isEqualTo(Ref.of("order_1"));
        assertThat(message.paymentRef()).isEqualTo(Ref.of("5114910"));
        if (outcome == Outcome.REFUNDED) {
            assertThat(message.factRef()).isEqualTo(Ref.of("11325632"));
            assertThat(message.amount()).isEqualTo(Money.of(200, "INR"));
        }
    }

    // ---- exact decimals ----------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1} → {2} minor units")
    @CsvSource({
        "1.8,    INR, 180",
        "1.80,   INR, 180",
        "2,      INR, 200",
        "2.00,   INR, 200",
        "0.01,   INR, 1",
        "1e2,    INR, 10000",
        "12345678.99, INR, 1234567899",
        "500,    JPY, 500",
        "1.234,  KWD, 1234"
    })
    void decimalAmountsAreConvertedExactly(String amount, String currency, long minor) {
        PaymentMessage message = parse(Cashfree.payment("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS", amount)
                .replace("\"payment_currency\":\"INR\"", "\"payment_currency\":\"" + currency + "\""));

        assertThat(message.amount()).isEqualTo(Money.of(minor, currency));
    }

    @ParameterizedTest(name = "{0} {1} → unparseable")
    @CsvSource(
            delimiter = '|',
            value = {
                "1.005|INR", // finer than paise: refused, never rounded
                "0.001|INR",
                "500.5|JPY",
                "-1|INR",
                "-0.01|INR",
                "\"1.80\"|INR", // a string, not a number
                "null|INR",
                "true|INR",
                "1e30|INR", // more than a long
                "1.80|ZZZ",
                "1.80|XAU" // gold: no minor unit
            })
    void amountsThatAreNotExactMinorUnitsAreRefused(String amount, String currency) {
        String body = Cashfree.payment("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS", amount)
                .replace("\"payment_currency\":\"INR\"", "\"payment_currency\":\"" + currency + "\"");

        assertThatThrownBy(() -> parse(body)).isInstanceOf(UnparseableMessageException.class);
    }

    @ParameterizedTest(name = "{0} → unparseable")
    @CsvSource(
            delimiter = '|',
            value = {
                "'{\"data\":{}}'",
                "'{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"data\":{\"order\":{\"order_id\":\"o\"},\"payment\":{\"payment_status\":\"SUCCESS\",\"payment_amount\":1,\"payment_currency\":\"INR\"}}}'",
                "'{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"data\":{\"order\":{},\"payment\":{\"cf_payment_id\":1,\"payment_status\":\"SUCCESS\",\"payment_amount\":1,\"payment_currency\":\"INR\"}}}'",
                "'{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"data\":{\"order\":{\"order_id\":\"o\"},\"payment\":{\"cf_payment_id\":1,\"payment_status\":\"SUCCESS\",\"payment_currency\":\"INR\"}}}'",
                "'{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"data\":{\"order\":{\"order_id\":\"o\"},\"payment\":{\"cf_payment_id\":1,\"payment_status\":\"SUCCESS\",\"payment_amount\":1}}}'",
                "'{\"type\":\"REFUND_STATUS_WEBHOOK\",\"data\":{\"refund\":{\"cf_payment_id\":1,\"order_id\":\"o\",\"refund_status\":\"SUCCESS\",\"refund_amount\":1,\"refund_currency\":\"INR\"}}}'",
                "'{\"type\":\"REFUND_STATUS_WEBHOOK\",\"data\":{\"refund\":{\"cf_refund_id\":1,\"order_id\":\"o\",\"refund_status\":\"SUCCESS\",\"refund_amount\":1,\"refund_currency\":\"INR\"}}}'",
                "'{\"type\":\"REFUND_STATUS_WEBHOOK\",\"data\":{\"refund\":{\"cf_refund_id\":1,\"cf_payment_id\":1,\"order_id\":\"o\",\"refund_status\":\"SUCCESS\",\"refund_currency\":\"INR\"}}}'"
            })
    void requiredFieldsAreNeverGuessed(String body) {
        assertThatThrownBy(() -> parse(body))
                .isInstanceOf(UnparseableMessageException.class)
                .hasMessageNotContaining("JSON");
    }

    // ---- outbound ----------------------------------------------------------------------------------

    @Test
    void createsOrdersWithTheClientCredentialsAndReadsTheReply() {
        RawMessage request = RawMessage.of(
                Map.of(),
                "{\"order_id\":\"order_1\",\"order_amount\":10.50,\"order_currency\":\"INR\","
                        + "\"customer_details\":{\"customer_id\":\"c1\",\"customer_phone\":\"9999999999\"}}");

        var facts = adapter.facts(CallKind.VALIDATION, request);
        assertThat(facts.merchantRef()).isEqualTo(Ref.of("order_1"));
        assertThat(facts.amount()).isEqualTo(Money.of(1_050, "INR"));
        PreparedCall call = adapter.prepare(CallKind.VALIDATION, request, Cashfree.connection());
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.uri()).hasToString("https://sandbox.cashfree.com/pg/orders");
        assertThat(call.headers())
                .containsEntry("x-client-id", "TEST-client-id")
                .containsEntry("x-client-secret", Cashfree.CLIENT_SECRET)
                .containsEntry("x-api-version", "2025-01-01");
        assertThat(call.toString()).doesNotContain(Cashfree.CLIENT_SECRET);

        Reply reply = adapter.reply(
                CallKind.VALIDATION,
                request,
                200,
                RawMessage.of(
                        Map.of(),
                        "{\"cf_order_id\":\"2149460581\",\"order_id\":\"order_1\",\"order_amount\":10.50,"
                                + "\"order_currency\":\"INR\",\"order_status\":\"ACTIVE\",\"payment_session_id\":\"s\"}"),
                Cashfree.connection(),
                Instant.now());
        assertThat(reply).isEqualTo(new Reply.Validated(true, Ref.of("order_1"), Money.of(1_050, "INR")));
        assertThat(adapter.reply(
                        CallKind.VALIDATION,
                        request,
                        400,
                        RawMessage.of(Map.of(), "{\"message\":\"bad\"}"),
                        Cashfree.connection(),
                        Instant.now()))
                .isEqualTo(new Reply.Validated(false, null, null));
    }

    /** Cashfree would default the currency and invent an order ID; Aegis never assumes either. */
    @ParameterizedTest(name = "order {0} → refused")
    @CsvSource(
            delimiter = '|',
            value = {
                "'{\"order_amount\":10.5,\"order_currency\":\"INR\"}'",
                "'{\"order_id\":\"order_1\",\"order_amount\":10.5}'",
                "'{\"order_id\":\"order_1\",\"order_amount\":\"10.5\",\"order_currency\":\"INR\"}'",
                "'{\"order_id\":\"order_1\",\"order_amount\":10.555,\"order_currency\":\"INR\"}'",
                "'{\"order_id\":\"../x\",\"order_amount\":10.5,\"order_currency\":\"INR\"}'"
            })
    void anOrderNeedsAnIdAnExactAmountAndACurrency(String body) {
        assertThatThrownBy(() -> adapter.facts(CallKind.VALIDATION, RawMessage.of(Map.of(), body)))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @ParameterizedTest(name = "status {0} → {1}")
    @CsvSource({
        "SUCCESS,       SUCCESS",
        "FAILED,        FAILED",
        "USER_DROPPED,  FAILED",
        "CANCELLED,     FAILED",
        "VOID,          FAILED",
        "PENDING,       PENDING",
        "NOT_ATTEMPTED, PENDING",
        "FLAGGED,       OTHER"
    })
    void statusRepliesAreObservationsOfThePayment(String status, Outcome outcome) {
        RawMessage request = RawMessage.of(Map.of(), "{\"order_id\":\"order_1\",\"cf_payment_id\":5114910}");
        PreparedCall call = adapter.prepare(CallKind.STATUS_CHECK, request, Cashfree.connection());
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.uri()).hasToString("https://sandbox.cashfree.com/pg/orders/order_1/payments/5114910");

        PaymentMessage message = ((Reply.Payment) adapter.reply(
                        CallKind.STATUS_CHECK,
                        request,
                        200,
                        RawMessage.of(
                                Map.of(),
                                "{\"cf_payment_id\":\"5114910\",\"order_id\":\"order_1\",\"payment_amount\":1.80,"
                                        + "\"payment_currency\":\"INR\",\"payment_status\":\"" + status + "\"}"),
                        Cashfree.connection(),
                        Instant.now()))
                .message();

        assertThat(message.type()).isEqualTo(MessageType.STATUS_CHECK);
        assertThat(message.outcome()).isEqualTo(outcome);
        assertThat(message.amount()).isEqualTo(outcome == Outcome.OTHER ? null : Money.of(180, "INR"));
        assertThat(message.paymentRef()).isEqualTo(Ref.of("5114910"));
        assertThat(message.sentAt()).isNull();
    }

    /** The IDs go into the provider URL with the merchant's credentials: never a path. */
    @ParameterizedTest(name = "status check {0} → refused")
    @CsvSource(
            delimiter = '|',
            value = {
                "'{\"order_id\":\"../orders\",\"cf_payment_id\":1}'",
                "'{\"order_id\":\"order_1\",\"cf_payment_id\":\"1/refunds\"}'",
                "'{\"order_id\":\"order_1\"}'",
                "'{\"cf_payment_id\":1}'",
                "'{\"order_id\":\"order_1\",\"cf_payment_id\":-1}'"
            })
    void statusChecksNeedSafeIds(String body) {
        assertThatThrownBy(() ->
                        adapter.prepare(CallKind.STATUS_CHECK, RawMessage.of(Map.of(), body), Cashfree.connection()))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void theProductionApiOnlyInProduction() {
        adapter.validate(Cashfree.connection("https://sandbox.cashfree.com/pg"), false);
        adapter.validate(Cashfree.connection("https://api.cashfree.com/pg"), true);

        assertThatThrownBy(() -> adapter.validate(Cashfree.connection("https://api.cashfree.com/pg"), false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.validate(Cashfree.connection("https://sandbox.cashfree.com/pg"), true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.validate(Cashfree.connection("https://API.cashfree.com/pg"), false))
                .isInstanceOf(IllegalStateException.class);
    }
}
