package dev.aegis.lab.npci;

import dev.aegis.lab.npci.Switch.Network;
import dev.aegis.lab.npci.Switch.Plan;
import dev.aegis.lab.npci.Switch.Txn;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The lab's handle on the switch: what the next payment does, what the switch saw, and attack
 * messages about a real transaction delivered to Aegis (F24 N1–N8).
 */
@RestController
@RequestMapping("/test")
class TestController {

    private static final KeyPair ATTACKER = XmlDsig.generate();

    private final Switch npci;

    TestController(Switch npci) {
        this.npci = npci;
    }

    @PutMapping("/next")
    void next(@RequestBody Plan plan) {
        npci.plan(plan);
    }

    record Bill(long amount) {}

    @PutMapping("/bill")
    void bill(@RequestBody Bill bill) {
        npci.billAmount(bill.amount());
    }

    @GetMapping("/received")
    Map<String, Integer> received() {
        return npci.received();
    }

    @GetMapping("/txn/{id}")
    ResponseEntity<Map<String, Object>> txn(@PathVariable String id) {
        return npci.txn(id)
                .map(t ->
                        ResponseEntity.ok(Map.<String, Object>of("status", t.status, "deliveries", t.deliveries.get())))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delivers one attack message about transaction {@code txn} and returns the receiver's status.
     * Every one claims SUCCESS: what an attacker wants the application to believe.
     */
    @PostMapping("/attack")
    ResponseEntity<Map<String, Object>> attack(@RequestParam String kind, @RequestParam String txn) {
        Txn target = npci.txn(txn).orElse(null);
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        String genuine = npci.result(target, "SUCCESS", Instant.now());
        String unsigned = success(target, Instant.now());
        String message = switch (kind) {
            case "forged" -> XmlDsig.sign(unsigned, ATTACKER.getPrivate());
            case "tampered" -> tamper(genuine, target);
            case "wrapped" -> wrap(genuine, target);
            case "unsigned" -> unsigned;
            case "xxe" ->
                "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                        + unsigned.replace("msgId=\"", "msgId=\"&x;");
            case "stale" -> npci.result(target, "SUCCESS", Instant.now().minus(Duration.ofHours(2)));
            case "sha1" -> XmlDsig.sign(unsigned, npci.switchKey(), SignatureMethod.RSA_SHA1, null);
            case "hmac" ->
                XmlDsig.sign(
                        unsigned,
                        new SecretKeySpec(npci.publicKeyBytes(), "HmacSHA256"),
                        SignatureMethod.HMAC_SHA256,
                        null);
            case "keyinfo" -> keyInfo(unsigned);
            case "replay" -> target.lastDelivered;
            default -> null;
        };
        if (message == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(Map.of("status", npci.deliver(target.network, message)));
    }

    private static String success(Txn txn, Instant at) {
        return switch (txn.network) {
            case BBPS -> Messages.billPaymentResponse(txn.ref, txn.id, "SUCCESS", txn.amount, at);
            case UPI -> Messages.respPay(txn.ref, txn.id, "PAY", null, "SUCCESS", txn.amount, at);
        };
    }

    /** Signed, then the amount changed to one paisa. */
    private static String tamper(String signed, Txn txn) {
        return txn.network == Network.BBPS
                ? signed.replace("amount=\"" + txn.amount + "\"", "amount=\"1\"")
                : signed.replace("settAmount=\"" + Messages.rupees(txn.amount) + "\"", "settAmount=\"0.01\"");
    }

    /**
     * Signature wrapping: a forged result (another transaction's success) whose root carries the
     * genuine signature, with the genuine signed document tucked inside it.
     */
    private String wrap(String genuine, Txn txn) {
        Document forged = XmlDsig.parse(
                success(new Txn(txn.network, txn.ref, "W" + txn.id, txn.amount, Plan.DEFAULT), Instant.now()));
        Document original = XmlDsig.parse(genuine);
        Element wrapper = forged.createElementNS(null, "Wrapper");
        forged.getDocumentElement().appendChild(wrapper);
        wrapper.appendChild(forged.importNode(original.getDocumentElement(), true));
        var signature =
                forged.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
        forged.getDocumentElement().appendChild(signature);
        return XmlDsig.write(forged);
    }

    /** Signed by the attacker, carrying the attacker's key for a careless verifier to use. */
    private static String keyInfo(String unsigned) {
        try {
            KeyInfoFactory keys = XMLSignatureFactory.getInstance("DOM").getKeyInfoFactory();
            return XmlDsig.sign(
                    unsigned,
                    ATTACKER.getPrivate(),
                    SignatureMethod.RSA_SHA256,
                    keys.newKeyInfo(List.of(keys.newKeyValue(ATTACKER.getPublic()))));
        } catch (java.security.KeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
