package dev.aegis.lab.npci;

import dev.aegis.lab.npci.Switch.Network;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Element;

/**
 * The switch's APIs. Every request must be signed by Aegis's key; anything else is answered 401 and
 * counted as {@code rejected}. Bill fetch, validation and status are answered at once; a payment
 * request gets an {@code Ack} and its result later (NPCI.md §5 explains the simplification).
 */
@RestController
class SwitchController {

    private final Switch npci;

    SwitchController(Switch npci) {
        this.npci = npci;
    }

    @PostMapping("/bbps/{api}")
    ResponseEntity<String> bbps(@PathVariable String api, @RequestBody String xml) {
        if (!npci.fromAegis(xml)) {
            npci.count("rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        npci.count(api);
        Element root = XmlDsig.parse(xml).getDocumentElement();
        Element head = child(root, "Head");
        String refId = head.getAttribute("refId");
        return switch (api) {
            case "BillFetchRequest" -> xml(npci.signed(Messages.billFetchResponse(refId, npci.billAmount())));
            case "BillPaymentRequest" -> {
                Element amount = child(child(root, "Amount"), "Amt");
                npci.pay(
                        Network.BBPS,
                        refId,
                        child(root, "Txn").getAttribute("txnReferenceId"),
                        Long.parseLong(amount.getAttribute("amount")));
                yield xml(Messages.ack(Messages.BBPS, "bbps", api, head.getAttribute("msgId")));
            }
            case "TxnStatusRequest" -> {
                String txn = child(root, "TxnStatusReq").getAttribute("txnReferenceId");
                yield npci.txn(txn)
                        .map(t -> xml(npci.signed(Messages.txnStatusResponse(refId, txn, t.status, t.amount))))
                        .orElse(ResponseEntity.notFound().build());
            }
            default -> ResponseEntity.notFound().build();
        };
    }

    @PostMapping("/upi/{api}")
    ResponseEntity<String> upi(@PathVariable String api, @RequestBody String xml) {
        if (!npci.fromAegis(xml)) {
            npci.count("rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        npci.count(api);
        Element root = XmlDsig.parse(xml).getDocumentElement();
        Element txn = child(root, "Txn");
        return switch (api) {
            case "ReqValAdd" -> {
                boolean known = child(root, "Payee").getAttribute("addr").endsWith("@bank");
                yield xml(npci.signed(Messages.respValAdd(txn.getAttribute("id"), known ? "SUCCESS" : "FAILURE")));
            }
            case "ReqPay" -> {
                BigDecimal rupees =
                        new BigDecimal(child(child(root, "Payer"), "Amount").getAttribute("value"));
                npci.pay(
                        Network.UPI,
                        txn.getAttribute("refId"),
                        txn.getAttribute("id"),
                        rupees.movePointRight(2).longValueExact());
                yield xml(Messages.ack(
                        Messages.UPI, "upi", api, child(root, "Head").getAttribute("msgId")));
            }
            case "ReqChkTxn" -> {
                String original = txn.getAttribute("orgTxnId");
                yield npci.txn(original)
                        .map(t -> {
                            boolean reversed = "REVERSED".equals(t.status);
                            return xml(npci.signed(Messages.respChkTxn(
                                    t.ref,
                                    original,
                                    reversed ? "REVERSAL" : "PAY",
                                    reversed ? "SUCCESS" : ("PENDING".equals(t.status) ? "DEEMED" : t.status),
                                    t.amount)));
                        })
                        .orElse(ResponseEntity.notFound().build());
            }
            default -> ResponseEntity.notFound().build();
        };
    }

    private static ResponseEntity<String> xml(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(body);
    }

    static Element child(Element parent, String name) {
        for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName())) {
                return e;
            }
        }
        throw new IllegalArgumentException(parent.getLocalName() + " without " + name);
    }
}
