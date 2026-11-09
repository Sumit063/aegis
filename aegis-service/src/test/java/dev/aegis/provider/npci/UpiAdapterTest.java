package dev.aegis.provider.npci;

import static dev.aegis.provider.npci.NpciMessages.CURRENT;
import static dev.aegis.provider.npci.NpciMessages.reqChkTxn;
import static dev.aegis.provider.npci.NpciMessages.reqPay;
import static dev.aegis.provider.npci.NpciMessages.reqValAdd;
import static dev.aegis.provider.npci.NpciMessages.respChkTxn;
import static dev.aegis.provider.npci.NpciMessages.respPay;
import static dev.aegis.provider.npci.NpciMessages.respValAdd;
import static dev.aegis.provider.npci.NpciMessages.signed;
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
import dev.aegis.provider.spi.Reply;
import dev.aegis.provider.spi.TestKeys;
import dev.aegis.provider.spi.UnparseableMessageException;
import dev.aegis.provider.spi.UntrustedReplyException;
import dev.aegis.provider.spi.Xml;
import dev.aegis.provider.spi.XmlSignature;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;

/** UPI at the payer's PSP (F22, docs/design/NPCI.md §3). */
class UpiAdapterTest {

    private final UpiAdapter adapter = new UpiAdapter();
    private final Connection connection = NpciMessages.connection("upi", "upi");
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private PaymentMessage parse(String xml) {
        return adapter.parse(Channel.SERVER, signed(xml, CURRENT), connection, now);
    }

    private PaymentMessage pay(String result) {
        return parse(respPay("V1", "T1", "PAY", null, result, "499.00", "M1", now));
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS, SUCCESS", "FAILURE, FAILED", "PENDING, PENDING", "DEEMED, PENDING", "TIMEOUT, OTHER"})
    void mapsEveryPaymentResult(String result, Outcome outcome) {
        assertThat(pay(result).outcome()).isEqualTo(outcome);
    }

    @Test
    void readsAPaymentResult() {
        PaymentMessage message = pay("SUCCESS");

        assertThat(message.type()).isEqualTo(MessageType.ASYNC_RESPONSE);
        assertThat(message.id()).isEqualTo(Ref.of("msg:M1"));
        assertThat(message.providerRef()).isEqualTo(Ref.of("V1"));
        assertThat(message.paymentRef()).isEqualTo(Ref.of("T1"));
        assertThat(message.amount()).isEqualTo(Money.of(49_900, "INR"));
        assertThat(message.sentAt()).isEqualTo(now);
        assertThat(message.factRef()).isNull();
    }

    /** A reversal returns the money of the payment it names; it is its own fact. */
    @Test
    void aSuccessfulReversalIsReversedForTheOriginalPayment() {
        PaymentMessage reversal = parse(respPay("V1", "RV1", "REVERSAL", "T1", "SUCCESS", "499.00", "M2", now));

        assertThat(reversal.outcome()).isEqualTo(Outcome.REVERSED);
        assertThat(reversal.paymentRef()).isEqualTo(Ref.of("T1"));
        assertThat(reversal.factRef()).isEqualTo(Ref.of("RV1"));
        assertThat(reversal.amount()).isEqualTo(Money.of(49_900, "INR"));
    }

    @Test
    void aReversalThatDidNotHappenIsNotNews() {
        PaymentMessage failed = parse(respPay("V1", "RV1", "REVERSAL", "T1", "FAILURE", "499.00", "M2", now));

        assertThat(failed.outcome()).isEqualTo(Outcome.OTHER);
        assertThat(failed.amount()).isNull();
    }

    /** Decimal major units are read exactly; finer than paise is refused, never rounded. */
    @ParameterizedTest
    @CsvSource({"499, 49900", "499.5, 49950", "0.01, 1", "499.00, 49900"})
    void readsDecimalAmountsExactly(String value, long paise) {
        assertThat(parse(respPay("V1", "T1", "PAY", null, "SUCCESS", value, "M1", now))
                        .amount())
                .isEqualTo(Money.of(paise, "INR"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "settAmount=\"499.00\" settCurrency=\"INR\" approvalNum=\"1\"/></Resp>|settAmount=\"499.005\" settCurrency=\"INR\" approvalNum=\"1\"/></Resp>",
                "settAmount=\"499.00\" settCurrency=\"INR\" approvalNum=\"1\"/></Resp>|settAmount=\"4.99e2\" settCurrency=\"INR\" approvalNum=\"1\"/></Resp>",
                "settAmount=\"499.00\" settCurrency=\"INR\" approvalNum=\"1\"/></Resp>|settAmount=\"-499.00\" settCurrency=\"INR\" approvalNum=\"1\"/></Resp>",
                "settAmount=\"499.00\" settCurrency=\"INR\" approvalNum=\"1\"/></Resp>|settAmount=\"499.00\" settCurrency=\"XYZ\" approvalNum=\"1\"/></Resp>",
                "<Ref type=\"PAYEE\"|<Ref type=\"PAYER\"",
                "type=\"PAY\"|type=\"COLLECT\"",
                "msgId=\"M1\"|msgId=\"M 1\"",
                "<Txn id=\"T1\"|<Txn id=\"T1/../x\""
            })
    void aMissingOrMalformedFieldIsUnparseable(String from, String to) {
        String xml = respPay("V1", "T1", "PAY", null, "SUCCESS", "499.00", "M1", now);
        assertThat(xml).contains(from);

        assertThatThrownBy(() -> parse(xml.replace(from, to))).isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void twoPayeeAmountsAreAmbiguousAndUnparseable() {
        String xml = respPay("V1", "T1", "PAY", null, "SUCCESS", "499.00", "M1", now)
                .replace("<Ref type=\"PAYER\"", "<Ref type=\"PAYEE\"");

        assertThatThrownBy(() -> parse(xml)).isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void readsWhatEachRequestSays() {
        assertThat(adapter.facts(CallKind.VALIDATION, unsigned(reqValAdd("V1", "ORDER-1", "499.00"))))
                .isEqualTo(new CallFacts(Ref.of("ORDER-1"), Ref.of("V1"), null, Money.of(49_900, "INR")));
        assertThat(adapter.facts(CallKind.PAY_REQUEST, unsigned(reqPay("V1", "T1", "499.00"))))
                .isEqualTo(new CallFacts(null, Ref.of("V1"), Ref.of("T1"), Money.of(49_900, "INR")));
        assertThat(adapter.facts(CallKind.STATUS_CHECK, unsigned(reqChkTxn("V1", "T1"))))
                .isEqualTo(new CallFacts(null, Ref.of("V1"), Ref.of("T1"), null));
    }

    @Test
    void aRequestOfTheWrongKindPreSignedForZeroOrNotAPaymentIsRefused() {
        assertThatThrownBy(() -> adapter.facts(CallKind.PAY_REQUEST, unsigned(reqValAdd("V1", "O", "1.00"))))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> adapter.facts(CallKind.PAY_REQUEST, signed(reqPay("V1", "T1", "1.00"), CURRENT)))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> adapter.facts(CallKind.VALIDATION, unsigned(reqValAdd("V1", "O", "0.00"))))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> adapter.facts(
                        CallKind.PAY_REQUEST,
                        unsigned(reqPay("V1", "T1", "1.00").replace("type=\"PAY\"", "type=\"REVERSAL\""))))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void signsTheRequestWithAegisKeyAndSendsItToTheSwitch() {
        PreparedCall call = adapter.prepare(CallKind.PAY_REQUEST, unsigned(reqPay("V1", "T1", "499.00")), connection);

        assertThat(call.uri()).isEqualTo(URI.create("https://switch.test/upi/ReqPay"));
        assertThat(XmlSignature.verify(Xml.parse(call.body()), List.of(TestKeys.AEGIS.getPublic())))
                .isTrue();
    }

    /** D34: the switch confirms the payee; the amount is the one the payer instructed. */
    @Test
    void aValidationRecordsThePayersAmountOnceThePayeeIsConfirmed() {
        var request = unsigned(reqValAdd("V1", "ORDER-1", "499.00"));

        assertThat(adapter.reply(
                        CallKind.VALIDATION,
                        request,
                        200,
                        signed(respValAdd("V1", "SUCCESS"), CURRENT),
                        connection,
                        now))
                .isEqualTo(new Reply.Validated(true, Ref.of("V1"), Money.of(49_900, "INR")));
        assertThat(adapter.reply(
                        CallKind.VALIDATION,
                        request,
                        200,
                        signed(respValAdd("V1", "FAILURE"), CURRENT),
                        connection,
                        now))
                .isEqualTo(new Reply.Validated(false, null, null));
        assertThatThrownBy(() -> adapter.reply(
                        CallKind.VALIDATION,
                        request,
                        200,
                        signed(respValAdd("V2", "SUCCESS"), CURRENT),
                        connection,
                        now))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> adapter.reply(
                        CallKind.VALIDATION, request, 200, unsigned(respValAdd("V1", "SUCCESS")), connection, now))
                .isInstanceOf(UntrustedReplyException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "PAY, SUCCESS, SUCCESS",
        "PAY, DEEMED, PENDING",
        "PAY, FAILURE, FAILED",
        "REVERSAL, SUCCESS, REVERSED",
        "REVERSAL, FAILURE, OTHER"
    })
    void aStatusReplyIsAnObservationOfThePayment(String type, String result, Outcome outcome) {
        Reply.Payment reply = (Reply.Payment) adapter.reply(
                CallKind.STATUS_CHECK,
                unsigned(reqChkTxn("V1", "T1")),
                200,
                signed(respChkTxn("V1", "T1", type, result, "499.00"), CURRENT),
                connection,
                now);

        assertThat(reply.message().outcome()).isEqualTo(outcome);
        assertThat(reply.message().paymentRef()).isEqualTo(Ref.of("T1"));
        assertThat(reply.message().type()).isEqualTo(MessageType.STATUS_CHECK);
    }

    @Test
    void aStatusReplyAboutAnotherTransactionOrUnsignedIsRefused() {
        var request = unsigned(reqChkTxn("V1", "T1"));

        assertThatThrownBy(() -> adapter.reply(
                        CallKind.STATUS_CHECK,
                        request,
                        200,
                        signed(respChkTxn("V1", "T2", "PAY", "SUCCESS", "1.00"), CURRENT),
                        connection,
                        now))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> adapter.reply(
                        CallKind.STATUS_CHECK,
                        request,
                        200,
                        signed(respChkTxn("V2", "T1", "PAY", "SUCCESS", "1.00"), CURRENT),
                        connection,
                        now))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> adapter.reply(
                        CallKind.STATUS_CHECK,
                        request,
                        200,
                        signed(respChkTxn("V1", "T1", "PAY", "SUCCESS", "1.00"), "attacker"),
                        connection,
                        now))
                .isInstanceOf(UntrustedReplyException.class);
    }

    @Test
    void acknowledgesInTheUpiNamespace() {
        Ack ack = adapter.ack(
                Channel.SERVER, signed(respPay("V1", "T1", "PAY", null, "SUCCESS", "1.00", "M9", now), CURRENT));

        Document body = Xml.parse(ack.body());
        assertThat(Xml.root(body, UpiAdapter.NS, "Ack").getAttribute("api")).isEqualTo("RespPay");
        assertThat(body.getDocumentElement().getAttribute("reqMsgId")).isEqualTo("M9");
    }

    @Test
    void aBbpsMessageSignedByTheSwitchIsNotAUpiMessage() {
        var bbps = signed(NpciMessages.billPaymentResponse("R1", "T1", "Successful", "000", 1, "M1", now), CURRENT);

        assertThat(adapter.verify(Channel.SERVER, bbps, connection)).isFalse();
    }
}
