package dev.aegis.provider.npci;

import static dev.aegis.provider.npci.NpciMessages.CURRENT;
import static dev.aegis.provider.npci.NpciMessages.billFetchRequest;
import static dev.aegis.provider.npci.NpciMessages.billFetchResponse;
import static dev.aegis.provider.npci.NpciMessages.billPaymentRequest;
import static dev.aegis.provider.npci.NpciMessages.billPaymentResponse;
import static dev.aegis.provider.npci.NpciMessages.signed;
import static dev.aegis.provider.npci.NpciMessages.txnStatusRequest;
import static dev.aegis.provider.npci.NpciMessages.txnStatusResponse;
import static dev.aegis.provider.npci.NpciMessages.unsigned;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Reply;
import dev.aegis.provider.spi.Secret;
import dev.aegis.provider.spi.SecretSet;
import dev.aegis.provider.spi.TestKeys;
import dev.aegis.provider.spi.UnparseableMessageException;
import dev.aegis.provider.spi.UntrustedReplyException;
import dev.aegis.provider.spi.Xml;
import dev.aegis.provider.spi.XmlSignature;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;

/** BBPS at the COU (F21, docs/design/NPCI.md §2). */
class BbpsAdapterTest {

    private final BbpsAdapter adapter = new BbpsAdapter();
    private final Connection connection = NpciMessages.connection("bbps", "bbps");
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private PaymentMessage parse(String xml) {
        return adapter.parse(Channel.SERVER, signed(xml, CURRENT), connection, now);
    }

    private PaymentMessage response(String reason, String code) {
        return parse(billPaymentResponse("R1", "T1", reason, code, 84_000, "M1", now));
    }

    @ParameterizedTest
    @CsvSource({"Successful, 000, SUCCESS", "Failure, E101, FAILED", "Pending, BFR011, PENDING", "Unknown, 000, OTHER"})
    void mapsEveryResult(String reason, String code, Outcome outcome) {
        assertThat(response(reason, code).outcome()).isEqualTo(outcome);
    }

    /** A reason and a code that disagree are never resolved in favour of either. */
    @ParameterizedTest
    @CsvSource({"Successful, E101", "Failure, 000"})
    void aReasonContradictingItsCodeIsUnparseable(String reason, String code) {
        assertThatThrownBy(() -> response(reason, code)).isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void readsReferencesAmountIdentityAndTime() {
        PaymentMessage message = response("Successful", "000");

        assertThat(message.type()).isEqualTo(MessageType.ASYNC_RESPONSE);
        assertThat(message.provider()).isEqualTo(Ref.of("bbps"));
        assertThat(message.id()).isEqualTo(Ref.of("msg:M1"));
        assertThat(message.providerRef()).isEqualTo(Ref.of("R1"));
        assertThat(message.paymentRef()).isEqualTo(Ref.of("T1"));
        assertThat(message.amount()).isEqualTo(Money.of(84_000, "INR"));
        assertThat(message.sentAt()).isEqualTo(now);
        assertThat(message.receivedAt()).isEqualTo(now);
    }

    @Test
    void anyOtherSignedMessageIsStoredButIsNotNews() {
        PaymentMessage other = parse(billFetchResponse("R1", "000", 84_000));

        assertThat(other.outcome()).isEqualTo(Outcome.OTHER);
        assertThat(other.paymentRef()).isNull();
        assertThat(other.amount()).isNull();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "msgId=\"M1\"|msgId=\"\"",
                "+05:30\" refId|\" refId",
                "<Txn txnReferenceId=\"T1\"/>|",
                "<Txn txnReferenceId=\"T1\"/>|<Txn txnReferenceId=\"T1\"/><Txn txnReferenceId=\"T2\"/>",
                "<Txn txnReferenceId=\"T1\"/>|<Txn txnReferenceId=\"../T1\"/>",
                "amount=\"84000\"|amount=\"840.00\"",
                "amount=\"84000\"|amount=\"-84000\"",
                "currency=\"356\"|currency=\"INR\"",
                "currency=\"356\"|currency=\"000\""
            })
    void aMissingOrMalformedFieldIsUnparseable(String from, String to) {
        String xml = billPaymentResponse("R1", "T1", "Successful", "000", 84_000, "M1", now);
        assertThat(xml).contains(from);

        assertThatThrownBy(() -> parse(xml.replace(from, to == null ? "" : to)))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void onlyTheSwitchsSignatureVerifiesAndOnlyOnTheServerChannel() {
        RawMessage genuine = signed(billPaymentResponse("R1", "T1", "Successful", "000", 1, "M1", now), CURRENT);
        RawMessage upi = signed(
                "<upi:RespPay xmlns:upi=\"http://npci.org/upi/schema/\"><Head msgId=\"M\" ts=\"" + NpciMessages.ts(now)
                        + "\"/></upi:RespPay>",
                CURRENT);

        assertThat(adapter.verify(Channel.SERVER, genuine, connection)).isTrue();
        assertThat(adapter.verify(Channel.BROWSER, genuine, connection)).isFalse();
        assertThat(adapter.verify(Channel.SERVER, upi, connection)).isFalse();
        assertThat(adapter.verify(Channel.SERVER, unsigned("<<<"), connection)).isFalse();
    }

    @Test
    void acknowledgesWithAnAckNamingTheMessage() {
        Ack ack = adapter.ack(
                Channel.SERVER, signed(billPaymentResponse("R1", "T1", "Pending", "BFR011", 1, "M7", now), CURRENT));

        Document body = Xml.parse(ack.body());
        assertThat(ack.status()).isEqualTo(200);
        assertThat(ack.contentType()).isEqualTo("application/xml");
        assertThat(Xml.root(body, BbpsAdapter.NS, "Ack").getAttribute("api")).isEqualTo("BillPaymentResponse");
        assertThat(body.getDocumentElement().getAttribute("reqMsgId")).isEqualTo("M7");
        assertThat(body.getDocumentElement().getAttribute("RspCd")).isEqualTo("Successful");
    }

    @Test
    void readsWhatEachRequestSays() {
        assertThat(adapter.facts(CallKind.VALIDATION, unsigned(billFetchRequest("R1"))))
                .isEqualTo(new CallFacts(Ref.of("R1"), Ref.of("R1"), null, null));
        assertThat(adapter.facts(CallKind.PAY_REQUEST, unsigned(billPaymentRequest("R1", "T1", 84_000))))
                .isEqualTo(new CallFacts(null, Ref.of("R1"), Ref.of("T1"), Money.of(84_000, "INR")));
        assertThat(adapter.facts(CallKind.STATUS_CHECK, unsigned(txnStatusRequest("R1", "T1"))))
                .isEqualTo(new CallFacts(null, Ref.of("R1"), Ref.of("T1"), null));
    }

    /** A request is what its endpoint says it is, unsigned (Aegis signs), with IDs that cannot be paths. */
    @Test
    void aRequestOfTheWrongKindPreSignedOrWithAnUnsafeIdIsRefused() {
        assertThatThrownBy(() -> adapter.facts(CallKind.PAY_REQUEST, unsigned(billFetchRequest("R1"))))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> adapter.facts(CallKind.VALIDATION, signed(billFetchRequest("R1"), CURRENT)))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> adapter.facts(CallKind.VALIDATION, unsigned(billFetchRequest("R1/../x"))))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> adapter.facts(CallKind.PAY_REQUEST, unsigned("<!DOCTYPE x []><x/>")))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void signsTheRequestWithAegisKeyAndSendsItToTheSwitch() {
        PreparedCall call =
                adapter.prepare(CallKind.PAY_REQUEST, unsigned(billPaymentRequest("R1", "T1", 84_000)), connection);

        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.uri()).isEqualTo(URI.create("https://switch.test/bbps/BillPaymentRequest"));
        assertThat(call.headers()).containsEntry("Content-Type", "application/xml");
        Document sent = Xml.parse(call.body());
        assertThat(XmlSignature.verify(sent, List.of(TestKeys.AEGIS.getPublic())))
                .isTrue();
        assertThat(Xml.attr(Xml.one(sent.getDocumentElement(), "Txn"), "txnReferenceId"))
                .isEqualTo("T1");
    }

    @Test
    void aFetchFixesTheBillAndItsAmount() {
        RawMessage request = unsigned(billFetchRequest("R1"));

        assertThat(adapter.reply(
                        CallKind.VALIDATION,
                        request,
                        200,
                        signed(billFetchResponse("R1", "000", 84_000), CURRENT),
                        connection,
                        now))
                .isEqualTo(new Reply.Validated(true, Ref.of("R1"), Money.of(84_000, "INR")));
        assertThat(adapter.reply(
                        CallKind.VALIDATION,
                        request,
                        200,
                        signed(billFetchResponse("R1", "BFR001", 84_000), CURRENT),
                        connection,
                        now))
                .isEqualTo(new Reply.Validated(false, null, null));
        assertThat(adapter.reply(CallKind.VALIDATION, request, 503, unsigned("down"), connection, now))
                .isEqualTo(new Reply.Validated(false, null, null));
    }

    /** D33: a reply not signed by the switch is untrusted (BAD_SIGNATURE), not merely unreadable. */
    @Test
    void anUnsignedOrForgedReplyIsUntrusted() {
        RawMessage request = unsigned(billFetchRequest("R1"));

        assertThatThrownBy(() -> adapter.reply(
                        CallKind.VALIDATION,
                        request,
                        200,
                        unsigned(billFetchResponse("R1", "000", 1)),
                        connection,
                        now))
                .isInstanceOf(UntrustedReplyException.class);
        assertThatThrownBy(() -> adapter.reply(
                        CallKind.VALIDATION,
                        request,
                        200,
                        signed(billFetchResponse("R1", "000", 1), "attacker"),
                        connection,
                        now))
                .isInstanceOf(UntrustedReplyException.class);
        assertThatThrownBy(() -> adapter.reply(
                        CallKind.STATUS_CHECK,
                        unsigned(txnStatusRequest("R1", "T1")),
                        200,
                        unsigned(txnStatusResponse("R1", "T1", "SUCCESS", 1)),
                        connection,
                        now))
                .isInstanceOf(UntrustedReplyException.class);
    }

    @Test
    void aReplyAboutAnotherBillOrTransactionIsRefused() {
        assertThatThrownBy(() -> adapter.reply(
                        CallKind.VALIDATION,
                        unsigned(billFetchRequest("R1")),
                        200,
                        signed(billFetchResponse("R2", "000", 1), CURRENT),
                        connection,
                        now))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> adapter.reply(
                        CallKind.STATUS_CHECK,
                        unsigned(txnStatusRequest("R1", "T1")),
                        200,
                        signed(txnStatusResponse("R1", "T2", "SUCCESS", 1), CURRENT),
                        connection,
                        now))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> adapter.reply(
                        CallKind.VALIDATION,
                        unsigned(billFetchRequest("R1")),
                        200,
                        signed(billFetchResponse("R1", "000", 0), CURRENT),
                        connection,
                        now))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS, SUCCESS", "FAILURE, FAILED", "PENDING, PENDING", "REFUNDED, OTHER"})
    void aStatusReplyIsAnObservationOfThePayment(String status, Outcome outcome) {
        Reply.Payment reply = (Reply.Payment) adapter.reply(
                CallKind.STATUS_CHECK,
                unsigned(txnStatusRequest("R1", "T1")),
                200,
                signed(txnStatusResponse("R1", "T1", status, 84_000), CURRENT),
                connection,
                now);

        PaymentMessage message = reply.message();
        assertThat(message.type()).isEqualTo(MessageType.STATUS_CHECK);
        assertThat(message.outcome()).isEqualTo(outcome);
        assertThat(message.providerRef()).isEqualTo(Ref.of("R1"));
        assertThat(message.paymentRef()).isEqualTo(Ref.of("T1"));
        assertThat(message.sentAt()).isNull();
        assertThat(message.amount()).isEqualTo(outcome == Outcome.OTHER ? null : Money.of(84_000, "INR"));
    }

    @Test
    void declaresNpciStatusCheckLimitsAndAnHourOfFreshness() {
        assertThat(adapter.enquiryLimits().firstAfter()).isEqualTo(Duration.ofSeconds(90));
        assertThat(adapter.enquiryLimits().maxChecks()).isEqualTo(3);
        assertThat(adapter.enquiryLimits().window()).isEqualTo(Duration.ofHours(2));
        assertThat(adapter.freshness()).isEqualTo(Duration.ofHours(1));
        assertThat(adapter.lateSuccessAfterFailure()).isFalse();
    }

    /** Fail closed at startup, naming the setting but never echoing key material. */
    @Test
    void aConnectionWithoutASwitchOrWithABadKeyDoesNotStart() {
        Connection noSwitch = new Connection("bbps", "bbps", "shop", null, Map.of(), connection.secrets());
        Connection badKey = new Connection(
                "bbps",
                "bbps",
                "shop",
                URI.create("https://switch.test"),
                Map.of(),
                new SecretSet(Map.of(
                        Npci.SIGNING_KEY, List.of(new Secret("a-password-is-not-a-private-key")),
                        Npci.SWITCH_CERTIFICATE, connection.secrets().all(Npci.SWITCH_CERTIFICATE))));
        Connection weakSwitch = new Connection(
                "bbps",
                "bbps",
                "shop",
                URI.create("https://switch.test"),
                Map.of(),
                new SecretSet(Map.of(
                        Npci.SIGNING_KEY, connection.secrets().all(Npci.SIGNING_KEY),
                        Npci.SWITCH_CERTIFICATE, List.of(new Secret(TestKeys.publicKey(TestKeys.generate(1024)))))));

        assertThatThrownBy(() -> adapter.validate(noSwitch, false)).hasMessageContaining("base-url");
        assertThatThrownBy(() -> adapter.validate(badKey, false))
                .hasMessageContaining("signing-key")
                .hasMessageNotContaining("a-password-is-not-a-private-key");
        assertThatThrownBy(() -> adapter.validate(weakSwitch, false)).hasMessageContaining("2048");
    }

    @Test
    void keepsTheRequestBytesItSignsReadable() {
        String request = billPaymentRequest("R1", "T1", 84_000);
        PreparedCall call = adapter.prepare(CallKind.PAY_REQUEST, unsigned(request), connection);

        assertThat(new String(call.body(), StandardCharsets.UTF_8)).contains("txnReferenceId=\"T1\"", "<ds:Signature");
    }
}
