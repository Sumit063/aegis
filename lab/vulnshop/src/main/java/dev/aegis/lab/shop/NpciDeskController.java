package dev.aegis.lab.shop;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bill and UPI desk behind Aegis (F23): it pays bills and UPI requests through Aegis and records
 * what Aegis tells it, in order, per transaction. It applies each Aegis message ID once, as every
 * application must (LLD §8). The NPCI lab (F24) reads the record to prove the application heard
 * exactly what happened, once.
 */
@RestController
class NpciDeskController {

    private final ShopProperties properties;
    private final JsonMapper json;
    private final Set<String> processed = ConcurrentHashMap.newKeySet();
    private final Map<String, List<String>> outcomes = new ConcurrentHashMap<>();

    NpciDeskController(ShopProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @PostMapping("/aegis/npci")
    ResponseEntity<String> message(
            @RequestBody String body,
            @RequestHeader(value = "X-Aegis-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Aegis-Signature", required = false) String signature) {
        if (!AegisSignature.verify(properties.aegisSecret(), timestamp, body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad Aegis signature");
        }
        JsonNode message = json.readTree(body);
        if (!processed.add(message.path("id").asString())) {
            return ResponseEntity.ok("ignored: message already processed");
        }
        if (!"PASSED".equals(message.path("verdict").asString())) {
            return ResponseEntity.ok("ignored: not passed");
        }
        String payment = message.path("paymentRef").asString();
        outcomes.computeIfAbsent(payment, k -> new CopyOnWriteArrayList<>())
                .add(message.path("outcome").asString());
        return ResponseEntity.ok("recorded");
    }

    /** What the desk was told about each transaction, in order. */
    @GetMapping("/npci/payments")
    Map<String, List<String>> payments() {
        return outcomes;
    }

    @GetMapping("/npci/payments/{ref}")
    List<String> payment(@PathVariable String ref) {
        return outcomes.getOrDefault(ref, List.of());
    }
}
