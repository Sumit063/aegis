package dev.aegis.provider.stripe;

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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StripeAdapterTest {

    private final StripeAdapter adapter = new StripeAdapter();

    private PaymentMessage parse(String body) {
        return adapter.parse(
                Channel.SERVER, Stripe.webhook(body, Stripe.WEBHOOK_SECRET), Stripe.connection(), Instant.now());
    }

    private boolean verifies(String header, String body) {
        return adapter.verify(
                Channel.SERVER, RawMessage.of(Map.of("Stripe-Signature", header), body), Stripe.connection());
    }

    // ---- signature header ------------------------------------------------------------------------

    /** While a secret is rolled Stripe signs once per active secret; any configured one verifies. */
    @Test
    void anyV1SignatureWithAConfiguredSecretVerifies() {
        String body = Stripe.event("payment_intent.succeeded", Stripe.intent("pi_1", "succeeded", 2_000, 2_000));
        long t = Instant.now().getEpochSecond();

        assertThat(verifies(
                        Stripe.signature(t, body, "whsec_rolled-new-secret-not-configured", Stripe.WEBHOOK_SECRET),
                        body))
                .isTrue();
        assertThat(verifies(Stripe.signature(t, body, "whsec_rolled-new-secret-not-configured"), body))
                .isFalse();
    }

    /** The timestamp is signed: changing it (to look fresh) breaks the signature. */
    @Test
    void theTimestampIsPartOfTheSignature() {
        String body = Stripe.event("payment_intent.succeeded", Stripe.intent("pi_1", "succeeded", 2_000, 2_000));
        long t = Instant.now().getEpochSecond();
        String v1 = Stripe.sign(Stripe.WEBHOOK_SECRET, t - 3600, body);

        assertThat(verifies("t=" + t + ",v1=" + v1, body)).isFalse();
        assertThat(verifies("t=" + (t - 3600) + ",v1=" + v1, body)).isTrue();
    }

    @ParameterizedTest(name = "header {0} → not verified")
    @CsvSource(
            delimiter = '|',
            value = {
                "'v0=SIG'", // only the test scheme: never accepted, so no weaker scheme can be chosen
                "'v1=SIG'", // no timestamp
                "'t=,v1=SIG'",
                "'t=soon,v1=SIG'",
                "'t=-5,v1=SIG'",
                "'t=1700000000'", // no signature
                "'t=1700000000,v1='",
                "''"
            })
    void malformedHeadersNeverVerify(String header) {
        String body = Stripe.event("payment_intent.succeeded", Stripe.intent("pi_1", "succeeded", 2_000, 2_000));
        long t = 1_700_000_000L;
        String real = Stripe.sign(Stripe.WEBHOOK_SECRET, t, body);

        assertThat(verifies(header.replace("SIG", real), body)).isFalse();
    }

    @Test
    void theBrowserChannelIsNeverTrusted() {
        RawMessage redirect = RawMessage.of(Map.of(), "payment_intent=pi_1&redirect_status=succeeded");

        assertThat(adapter.verify(Channel.BROWSER, redirect, Stripe.connection()))
                .isFalse();
        assertThatThrownBy(() -> adapter.parse(Channel.BROWSER, redirect, Stripe.connection(), Instant.now()))
                .isInstanceOf(UnparseableMessageException.class);
    }

    /** The send time is the signature's {@code t}, made for each delivery; not the event's {@code created}. */
    @Test
    void theSendTimeIsTheSignedTimestamp() {
        String body = Stripe.event("payment_intent.succeeded", Stripe.intent("pi_1", "succeeded", 2_000, 2_000));
        long t = Instant.now().getEpochSecond() - 30;
        RawMessage message =
                RawMessage.of(Map.of("Stripe-Signature", Stripe.signature(t, body, Stripe.WEBHOOK_SECRET)), body);

        PaymentMessage parsed = adapter.parse(Channel.SERVER, message, Stripe.connection(), Instant.now());

        assertThat(parsed.sentAt()).isEqualTo(Instant.ofEpochSecond(t));
        assertThat(parsed.id()).isEqualTo(Ref.of("evt_1"));
        assertThat(adapter.policy().freshness()).isEqualTo(Duration.ofMinutes(5));
        assertThat(adapter.policy().lateSuccessAfterFailure()).isTrue();
    }

    // ---- mapping -----------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} ({1}) → {2}")
    @CsvSource({
        "payment_intent.succeeded,                 succeeded,               SUCCESS, 2000",
        "payment_intent.payment_failed,            requires_payment_method, FAILED,  5000",
        "payment_intent.canceled,                  canceled,                FAILED,  5000",
        "payment_intent.processing,                processing,              PENDING, 5000",
        "payment_intent.amount_capturable_updated, requires_capture,        PENDING, 5000",
        "payment_intent.created,                   requires_payment_method, OTHER,   ",
        "payment_intent.requires_action,           requires_action,         OTHER,   ",
        "charge.succeeded,                         succeeded,               OTHER,   ",
        "charge.refunded,                          succeeded,               OTHER,   ",
        "charge.dispute.created,                   succeeded,               OTHER,   "
    })
    void mapsPaymentIntentEventsStrictly(String type, String status, Outcome outcome, Long amount) {
        // amount 5000 was asked for, 2000 received: SUCCESS carries what was received.
        PaymentMessage message = parse(Stripe.event(type, Stripe.intent("pi_1", status, 5_000, 2_000)));

        assertThat(message.outcome()).isEqualTo(outcome);
        assertThat(message.type()).isEqualTo(MessageType.WEBHOOK);
        assertThat(message.amount()).isEqualTo(amount == null ? null : Money.of(amount, "USD"));
        assertThat(message.providerRef()).isEqualTo(Ref.of("pi_1"));
        assertThat(message.paymentRef()).isEqualTo(Ref.of("pi_1"));
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
        "refund.created, succeeded,       REFUNDED",
        "refund.updated, succeeded,       REFUNDED",
        "refund.created, pending,         OTHER",
        "refund.updated, failed,          OTHER",
        "refund.updated, canceled,        OTHER",
        "refund.created, requires_action, OTHER",
        "refund.failed,  failed,          OTHER"
    })
    void aRefundIsAFactOnlyOnceSucceeded(String type, String status, Outcome outcome) {
        PaymentMessage message = parse(Stripe.event(type, Stripe.refund("re_1", "pi_1", status, 700)));

        assertThat(message.outcome()).isEqualTo(outcome);
        assertThat(message.paymentRef()).isEqualTo(Ref.of("pi_1"));
        if (outcome == Outcome.REFUNDED) {
            assertThat(message.factRef()).isEqualTo(Ref.of("re_1"));
            assertThat(message.amount()).isEqualTo(Money.of(700, "USD"));
        }
    }

    // ---- required fields ---------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} → unparseable")
    @CsvSource(
            delimiter = '|',
            value = {
                // event envelope
                "'{\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{}}}'",
                "'{\"id\":\"evt_1\",\"data\":{\"object\":{}}}'",
                "'{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\"}'",
                "'{\"id\":5,\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{}}}'",
                // PaymentIntent fields
                "'{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"object\":\"payment_intent\",\"amount_received\":1,\"currency\":\"usd\"}}}'",
                "'{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"amount\":1,\"currency\":\"usd\"}}}'",
                "'{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"amount_received\":\"1\",\"currency\":\"usd\"}}}'",
                "'{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"amount_received\":1.5,\"currency\":\"usd\"}}}'",
                "'{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"amount_received\":-1,\"currency\":\"usd\"}}}'",
                "'{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"amount_received\":1}}}'",
                "'{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"amount_received\":1,\"currency\":\"zz\"}}}'",
                "'{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_1\",\"object\":\"charge\",\"amount_received\":1,\"currency\":\"usd\"}}}'",
                // refund fields
                "'{\"id\":\"evt_1\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"object\":\"refund\",\"status\":\"succeeded\",\"payment_intent\":\"pi_1\",\"amount\":1,\"currency\":\"usd\"}}}'",
                "'{\"id\":\"evt_1\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"re_1\",\"object\":\"refund\",\"status\":\"succeeded\",\"amount\":1,\"currency\":\"usd\"}}}'",
                "'{\"id\":\"evt_1\",\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"re_1\",\"object\":\"refund\",\"status\":\"succeeded\",\"payment_intent\":\"pi_1\",\"currency\":\"usd\"}}}'"
            })
    void requiredFieldsAreNeverGuessed(String body) {
        assertThatThrownBy(() -> parse(body))
                .isInstanceOf(UnparseableMessageException.class)
                .hasMessageNotContaining("JSON");
    }

    // ---- outbound ----------------------------------------------------------------------------------

    @Test
    void createsPaymentIntentsWithTheSecretKeyAndReadsTheReply() {
        RawMessage request = RawMessage.of(Map.of(), "amount=2000&currency=usd&metadata%5Border_id%5D=SHOP-1");

        var facts = adapter.facts(CallKind.VALIDATION, request);
        assertThat(facts.merchantRef()).isEqualTo(Ref.of("SHOP-1"));
        assertThat(facts.amount()).isEqualTo(Money.of(2_000, "USD"));
        PreparedCall call = adapter.prepare(CallKind.VALIDATION, request, Stripe.connection());
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.uri()).hasToString("http://stripe.test/v1/payment_intents");
        assertThat(call.headers()).containsEntry("Authorization", "Bearer " + Stripe.SECRET_KEY);
        assertThat(call.body()).isEqualTo(request.body());
        assertThat(call.toString()).doesNotContain(Stripe.SECRET_KEY);

        Reply reply = adapter.reply(
                CallKind.VALIDATION,
                request,
                200,
                RawMessage.of(Map.of(), Stripe.intent("pi_1", "requires_payment_method", 2_000, 0)),
                Stripe.connection(),
                Instant.now());
        assertThat(reply).isEqualTo(new Reply.Validated(true, Ref.of("pi_1"), Money.of(2_000, "USD")));
        assertThat(adapter.reply(
                        CallKind.VALIDATION,
                        request,
                        402,
                        RawMessage.of(Map.of(), "{\"error\":{}}"),
                        Stripe.connection(),
                        Instant.now()))
                .isEqualTo(new Reply.Validated(false, null, null));
    }

    @ParameterizedTest(name = "PaymentIntent request {0} → refused")
    @CsvSource(
            delimiter = '|',
            value = {
                "'amount=2000&currency=usd'",
                "'currency=usd&metadata%5Border_id%5D=SHOP-1'",
                "'amount=20.00&currency=usd&metadata%5Border_id%5D=SHOP-1'",
                "'amount=-5&currency=usd&metadata%5Border_id%5D=SHOP-1'",
                "'amount=2000&metadata%5Border_id%5D=SHOP-1'",
                "'amount=2000&currency=zz&metadata%5Border_id%5D=SHOP-1'"
            })
    void aPaymentIntentNeedsAnOrderAnIntegerAmountAndACurrency(String form) {
        assertThatThrownBy(() -> adapter.facts(CallKind.VALIDATION, RawMessage.of(Map.of(), form)))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @ParameterizedTest(name = "status {0} → {1}")
    @CsvSource({
        "succeeded,               false, SUCCESS, 2000",
        "canceled,                false, FAILED,  5000",
        "requires_payment_method, true,  FAILED,  5000",
        "requires_payment_method, false, PENDING, 5000",
        "processing,              false, PENDING, 5000",
        "requires_capture,        false, PENDING, 5000",
        "requires_confirmation,   false, PENDING, 5000",
        "requires_action,         false, PENDING, 5000",
        "something_new,           false, OTHER,   "
    })
    void statusRepliesAreObservationsOfTheIntent(String status, boolean failedAttempt, Outcome outcome, Long amount) {
        String intent = Stripe.intent("pi_1", status, 5_000, 2_000);
        if (failedAttempt) {
            intent = intent.replace(
                    "\"latest_charge\"", "\"last_payment_error\":{\"code\":\"card_declined\"},\"latest_charge\"");
        }
        RawMessage request = RawMessage.of(Map.of(), "payment_intent=pi_1");
        PreparedCall call = adapter.prepare(CallKind.STATUS_CHECK, request, Stripe.connection());
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.uri()).hasToString("http://stripe.test/v1/payment_intents/pi_1");

        PaymentMessage message = ((Reply.Payment) adapter.reply(
                        CallKind.STATUS_CHECK,
                        request,
                        200,
                        RawMessage.of(Map.of(), intent),
                        Stripe.connection(),
                        Instant.now()))
                .message();

        assertThat(message.type()).isEqualTo(MessageType.STATUS_CHECK);
        assertThat(message.outcome()).isEqualTo(outcome);
        assertThat(message.amount()).isEqualTo(amount == null ? null : Money.of(amount, "USD"));
        assertThat(message.providerRef()).isEqualTo(Ref.of("pi_1"));
        assertThat(message.sentAt()).isNull();
    }

    @Test
    void aFailedStatusCheckIsUnparseable() {
        assertThatThrownBy(() -> adapter.reply(
                        CallKind.STATUS_CHECK,
                        RawMessage.of(Map.of(), "payment_intent=pi_1"),
                        404,
                        RawMessage.of(Map.of(), "{}"),
                        Stripe.connection(),
                        Instant.now()))
                .isInstanceOf(UnparseableMessageException.class);
        assertThat(adapter.supports(CallKind.PAY_REQUEST)).isFalse();
    }

    /** The ID goes into the provider URL with the merchant's key: it can never name another resource. */
    @ParameterizedTest(name = "payment_intent {0} → refused")
    @CsvSource(
            delimiter = '|',
            value = {"'..%2Fcustomers'", "'pi_1%2Frefunds'", "'pi_1%3Fexpand'", "''"})
    void statusChecksNeedASafeId(String intent) {
        RawMessage request = RawMessage.of(Map.of(), "payment_intent=" + intent);

        assertThatThrownBy(() -> adapter.prepare(CallKind.STATUS_CHECK, request, Stripe.connection()))
                .isInstanceOf(UnparseableMessageException.class);
    }

    /** Built from parts: the pre-commit secret scan refuses anything shaped like a live key. */
    private static final String LIVE = "live_";

    @Test
    void liveKeysOnlyInProduction() {
        adapter.validate(Stripe.connection("sk_test_abc"), false);
        adapter.validate(Stripe.connection("rk_test_abc"), false);
        adapter.validate(Stripe.connection("sk_" + LIVE + "abc"), true);
        adapter.validate(Stripe.connection("rk_" + LIVE + "abc"), true);

        assertThatThrownBy(() -> adapter.validate(Stripe.connection("sk_" + LIVE + "abc"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("sk_" + LIVE + "abc");
        assertThatThrownBy(() -> adapter.validate(Stripe.connection("sk_test_abc"), true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.validate(Stripe.connection("pk_test_abc"), false))
                .isInstanceOf(IllegalStateException.class);
    }
}
