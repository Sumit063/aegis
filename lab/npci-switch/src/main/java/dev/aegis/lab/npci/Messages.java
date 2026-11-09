package dev.aegis.lab.npci;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** The switch's messages (docs/design/NPCI.md §2, §3), unsigned. */
final class Messages {

    static final String BBPS = "http://bbps.org/schema";
    static final String UPI = "http://npci.org/upi/schema/";

    private Messages() {}

    static String ts(Instant at) {
        return OffsetDateTime.ofInstant(at.truncatedTo(ChronoUnit.SECONDS), ZoneOffset.ofHoursMinutes(5, 30))
                .toString();
    }

    static String msgId() {
        return "NPCI" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /** UPI amounts are decimal rupees: 49900 paise is {@code 499.00}. */
    static String rupees(long paise) {
        return BigDecimal.valueOf(paise, 2).toPlainString();
    }

    static String ack(String namespace, String prefix, String api, String reqMsgId) {
        return "<%s:Ack xmlns:%s=\"%s\" api=\"%s\" reqMsgId=\"%s\" ts=\"%s\" RspCd=\"Successful\"/>"
                .formatted(prefix, prefix, namespace, api, reqMsgId, ts(Instant.now()));
    }

    // ---- BBPS ----

    static String billFetchResponse(String refId, long amount) {
        return """
                <bbps:BillFetchResponse xmlns:bbps="%s"><Head ver="1.0" ts="%s" refId="%s" msgId="%s"/>\
                <Reason responseCode="000" responseReason="Successful"/>\
                <BillerResponse amount="%d" currency="356" billNumber="B-%s" dueDate="2026-12-31" customerName="Lab customer"/>\
                </bbps:BillFetchResponse>""".formatted(BBPS, ts(Instant.now()), refId, msgId(), amount, refId);
    }

    /** {@code status}: SUCCESS, FAILURE or PENDING (deemed). */
    static String billPaymentResponse(String refId, String txn, String status, long amount, Instant at) {
        String reason = switch (status) {
            case "SUCCESS" -> "responseCode=\"000\" responseReason=\"Successful\"";
            case "FAILURE" -> "responseCode=\"BFR005\" responseReason=\"Failure\"";
            default -> "responseCode=\"BFR011\" responseReason=\"Pending\"";
        };
        return """
                <bbps:BillPaymentResponse xmlns:bbps="%s"><Head ver="1.0" ts="%s" refId="%s" msgId="%s"/>\
                <Reason %s approvalRefNum="AP%s"/><Txn txnReferenceId="%s"/>\
                <Amount><Amt amount="%d" currency="356"/></Amount></bbps:BillPaymentResponse>""".formatted(BBPS, ts(at), refId, msgId(), reason, txn, txn, amount);
    }

    static String txnStatusResponse(String refId, String txn, String status, long amount) {
        return """
                <bbps:TxnStatusResponse xmlns:bbps="%s"><Head ver="1.0" ts="%s" refId="%s" msgId="%s"/>\
                <Reason responseCode="000" responseReason="Successful"/>\
                <TxnStatusResp><Txn txnReferenceId="%s" status="%s"><Amount><Amt amount="%d" currency="356"/></Amount></Txn></TxnStatusResp>\
                </bbps:TxnStatusResponse>""".formatted(BBPS, ts(Instant.now()), refId, msgId(), txn, status, amount);
    }

    // ---- UPI ----

    static String respValAdd(String txn, String result) {
        return """
                <upi:RespValAdd xmlns:upi="%s"><Head ver="2.0" ts="%s" orgId="NPCI" msgId="%s"/>\
                <Txn id="%s" type="ValAdd"/><Resp result="%s" maskName="LAB SHOP"/></upi:RespValAdd>""".formatted(UPI, ts(Instant.now()), msgId(), txn, result);
    }

    /** {@code result} SUCCESS, FAILURE or DEEMED; a reversal names its payment in {@code orgTxn}. */
    static String respPay(String refId, String txn, String type, String orgTxn, String result, long paise, Instant at) {
        String original = orgTxn == null ? "" : " orgTxnId=\"" + orgTxn + "\"";
        return """
                <upi:RespPay xmlns:upi="%s"><Head ver="2.0" ts="%s" orgId="NPCI" msgId="%s"/>\
                <Txn id="%s" refId="%s" type="%s"%s/><Resp result="%s" errCode="">\
                <Ref type="PAYER" settAmount="%s" settCurrency="INR" approvalNum="1"/>\
                <Ref type="PAYEE" settAmount="%s" settCurrency="INR" approvalNum="1"/></Resp></upi:RespPay>""".formatted(UPI, ts(at), msgId(), txn, refId, type, original, result, rupees(paise), rupees(paise));
    }

    static String respChkTxn(String refId, String txn, String type, String result, long paise) {
        return """
                <upi:RespChkTxn xmlns:upi="%s"><Head ver="2.0" ts="%s" orgId="NPCI" msgId="%s"/>\
                <Txn id="C%s" refId="%s" orgTxnId="%s" type="%s"/>\
                <Resp result="%s" settAmount="%s" settCurrency="INR"/></upi:RespChkTxn>""".formatted(UPI, ts(Instant.now()), msgId(), txn, refId, txn, type, result, rupees(paise));
    }
}
