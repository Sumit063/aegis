package dev.aegis.provider.npci;

import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Secret;
import dev.aegis.provider.spi.SecretSet;
import dev.aegis.provider.spi.TestKeys;
import dev.aegis.provider.spi.Xml;
import dev.aegis.provider.spi.XmlSignature;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;

/**
 * NPCI-style test messages (docs/design/NPCI.md), signed the way the switch signs them. Key names
 * stand in for the contract suite's secrets: {@code current} and {@code previous} are the configured
 * switch keys; any other name is a key the connection has never seen.
 */
public final class NpciMessages {

    public static final String CURRENT = "current";
    public static final String PREVIOUS = "previous";

    private NpciMessages() {}

    /** A connection that trusts the current and previous switch keys and signs with Aegis's test key. */
    public static Connection connection(String name, String adapter) {
        return new Connection(
                name,
                adapter,
                "shop",
                URI.create("https://switch.test"),
                Map.of(),
                new SecretSet(Map.of(
                        Npci.SIGNING_KEY,
                        List.of(new Secret(TestKeys.privateKey(TestKeys.AEGIS))),
                        Npci.SWITCH_CERTIFICATE,
                        List.of(
                                new Secret(TestKeys.publicKey(TestKeys.CURRENT)),
                                new Secret(TestKeys.publicKey(TestKeys.PREVIOUS))))));
    }

    public static KeyPair key(String name) {
        return switch (name) {
            case CURRENT -> TestKeys.CURRENT;
            case PREVIOUS -> TestKeys.PREVIOUS;
            default -> TestKeys.ATTACKER;
        };
    }

    /** {@code xml} signed with the named key, as delivered over HTTP. */
    public static RawMessage signed(String xml, String key) {
        return signed(xml, key(key));
    }

    public static RawMessage signed(String xml, KeyPair key) {
        Document document = Xml.parse(xml.getBytes(StandardCharsets.UTF_8));
        XmlSignature.sign(document, key.getPrivate());
        return RawMessage.of(
                Map.of("Content-Type", "application/xml"), new String(Xml.write(document), StandardCharsets.UTF_8));
    }

    public static RawMessage unsigned(String xml) {
        return RawMessage.of(Map.of("Content-Type", "application/xml"), xml);
    }

    /** The message with its signature element removed. */
    public static RawMessage withoutSignature(RawMessage message) {
        String xml = message.text();
        int start = xml.indexOf("<ds:Signature");
        int end = xml.indexOf("</ds:Signature>") + "</ds:Signature>".length();
        return RawMessage.of(Map.of("Content-Type", "application/xml"), xml.substring(0, start) + xml.substring(end));
    }

    public static String ts(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.ofHoursMinutes(5, 30)).toString();
    }

    // ---- UPI (NPCI.md §3) ----

    private static final String UPI = "xmlns:upi=\"http://npci.org/upi/schema/\"";

    public static String reqValAdd(String validation, String order, String amount) {
        return """
                <upi:ReqValAdd %s><Head ver="2.0" ts="%s" orgId="AEGISPSP" msgId="V%s"/>\
                <Txn id="%s" refId="%s" note="Order %s" type="ValAdd" ts="%s"/>\
                <Payer addr="customer@psp"/>\
                <Payee addr="shop@bank"><Amount value="%s" curr="INR"/></Payee>\
                </upi:ReqValAdd>""".formatted(UPI, ts(Instant.now()), validation, validation, order, order, ts(Instant.now()), amount);
    }

    public static String respValAdd(String validation, String result) {
        return """
                <upi:RespValAdd %s><Head ver="2.0" ts="%s" orgId="NPCI" msgId="VR%s"/>\
                <Txn id="%s" type="ValAdd"/>\
                <Resp reqMsgId="V%s" result="%s" maskName="SHOP"/>\
                </upi:RespValAdd>""".formatted(UPI, ts(Instant.now()), validation, validation, validation, result);
    }

    public static String reqPay(String validation, String txn, String amount) {
        return """
                <upi:ReqPay %s><Head ver="2.0" ts="%s" orgId="AEGISPSP" msgId="P%s"/>\
                <Txn id="%s" refId="%s" type="PAY" note="Order" ts="%s"/>\
                <Payer addr="customer@psp"><Amount value="%s" curr="INR"/></Payer>\
                <Payee addr="shop@bank"/>\
                </upi:ReqPay>""".formatted(UPI, ts(Instant.now()), txn, txn, validation, ts(Instant.now()), amount);
    }

    /** {@code type} PAY or REVERSAL; a reversal's own ID is {@code txn}, the payment it reverses {@code orgTxn}. */
    public static String respPay(
            String validation,
            String txn,
            String type,
            String orgTxn,
            String result,
            String settled,
            String msgId,
            Instant at) {
        String original = orgTxn == null ? "" : " orgTxnId=\"" + orgTxn + "\"";
        return """
                <upi:RespPay %s><Head ver="2.0" ts="%s" orgId="NPCI" msgId="%s"/>\
                <Txn id="%s" refId="%s" type="%s"%s/>\
                <Resp reqMsgId="P%s" result="%s" errCode="">\
                <Ref type="PAYER" settAmount="%s" settCurrency="INR" approvalNum="1"/>\
                <Ref type="PAYEE" settAmount="%s" settCurrency="INR" approvalNum="1"/>\
                </Resp></upi:RespPay>""".formatted(UPI, ts(at), msgId, txn, validation, type, original, txn, result, settled, settled);
    }

    public static String reqChkTxn(String validation, String txn) {
        return """
                <upi:ReqChkTxn %s><Head ver="2.0" ts="%s" orgId="AEGISPSP" msgId="C%s"/>\
                <Txn id="C%s" refId="%s" orgTxnId="%s" type="ChkTxn"/>\
                </upi:ReqChkTxn>""".formatted(UPI, ts(Instant.now()), txn, txn, validation, txn);
    }

    public static String respChkTxn(String validation, String txn, String type, String result, String settled) {
        return """
                <upi:RespChkTxn %s><Head ver="2.0" ts="%s" orgId="NPCI" msgId="CR%s"/>\
                <Txn id="C%s" refId="%s" orgTxnId="%s" type="%s"/>\
                <Resp result="%s" settAmount="%s" settCurrency="INR"/>\
                </upi:RespChkTxn>""".formatted(UPI, ts(Instant.now()), txn, txn, validation, txn, type, result, settled);
    }

    // ---- BBPS (NPCI.md §2) ----

    public static String billFetchRequest(String refId) {
        return """
                <bbps:BillFetchRequest xmlns:bbps="http://bbps.org/schema">\
                <Head ver="1.0" ts="%s" origInst="AEGISCOU" refId="%s" msgId="F%s"/>\
                <BillDetails><Biller id="ELEC00000NAT01"/>\
                <CustomerParams><Tag name="ConsumerNumber" value="1234567890"/></CustomerParams></BillDetails>\
                </bbps:BillFetchRequest>""".formatted(ts(Instant.now()), refId, refId);
    }

    public static String billFetchResponse(String refId, String code, long amount) {
        return """
                <bbps:BillFetchResponse xmlns:bbps="http://bbps.org/schema">\
                <Head ver="1.0" ts="%s" refId="%s" msgId="FR%s"/>\
                <Reason responseCode="%s" responseReason="%s"/>\
                <BillerResponse amount="%d" currency="356" billNumber="B-1" dueDate="2026-11-30" customerName="Test"/>\
                </bbps:BillFetchResponse>""".formatted(
                        ts(Instant.now()), refId, refId, code, "000".equals(code) ? "Successful" : "Failure", amount);
    }

    public static String billPaymentRequest(String refId, String txn, long amount) {
        return """
                <bbps:BillPaymentRequest xmlns:bbps="http://bbps.org/schema">\
                <Head ver="1.0" ts="%s" origInst="AEGISCOU" refId="%s" msgId="P%s"/>\
                <Txn txnReferenceId="%s" ts="%s"/>\
                <BillDetails><Biller id="ELEC00000NAT01"/></BillDetails>\
                <Amount><Amt amount="%d" currency="356"/></Amount>\
                <PaymentMethod paymentMode="UPI"/>\
                </bbps:BillPaymentRequest>""".formatted(ts(Instant.now()), refId, txn, txn, ts(Instant.now()), amount);
    }

    public static String billPaymentResponse(
            String refId, String txn, String reason, String code, long amount, String msgId, Instant at) {
        return """
                <bbps:BillPaymentResponse xmlns:bbps="http://bbps.org/schema">\
                <Head ver="1.0" ts="%s" refId="%s" msgId="%s"/>\
                <Reason responseCode="%s" responseReason="%s" approvalRefNum="AB%s"/>\
                <Txn txnReferenceId="%s"/>\
                <Amount><Amt amount="%d" currency="356"/></Amount>\
                </bbps:BillPaymentResponse>""".formatted(ts(at), refId, msgId, code, reason, txn, txn, amount);
    }

    public static String txnStatusRequest(String refId, String txn) {
        return """
                <bbps:TxnStatusRequest xmlns:bbps="http://bbps.org/schema">\
                <Head ver="1.0" ts="%s" origInst="AEGISCOU" refId="%s" msgId="S%s"/>\
                <TxnStatusReq txnReferenceId="%s"/>\
                </bbps:TxnStatusRequest>""".formatted(ts(Instant.now()), refId, txn, txn);
    }

    public static String txnStatusResponse(String refId, String txn, String status, long amount) {
        return """
                <bbps:TxnStatusResponse xmlns:bbps="http://bbps.org/schema">\
                <Head ver="1.0" ts="%s" refId="%s" msgId="SR%s"/>\
                <Reason responseCode="000" responseReason="Successful"/>\
                <TxnStatusResp><Txn txnReferenceId="%s" status="%s"><Amount><Amt amount="%d" currency="356"/></Amount></Txn></TxnStatusResp>\
                </bbps:TxnStatusResponse>""".formatted(ts(Instant.now()), refId, txn, txn, status, amount);
    }
}
