package dev.aegis.lab.gateway;

import dev.aegis.lab.gateway.GatewayProperties.Account;
import dev.aegis.lab.gateway.Model.Order;
import dev.aegis.lab.gateway.Model.Payment;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The subset of Razorpay's REST API the lab uses, with HTTP Basic authentication by API key. */
@RestController
@RequestMapping("/v1")
class ApiController {

    record CreateOrder(Long amount, String currency, String receipt, Map<String, String> notes) {}

    record Collection<T>(String entity, int count, List<T> items) {}

    private final GatewayProperties properties;
    private final Ledger ledger;

    ApiController(GatewayProperties properties, Ledger ledger) {
        this.properties = properties;
        this.ledger = ledger;
    }

    @PostMapping("/orders")
    Order createOrder(
            @RequestHeader(value = "Authorization", required = false) String auth, @RequestBody CreateOrder body) {
        if (body.amount() == null) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "The amount field is required.");
        }
        return ledger.createOrder(account(auth), body.amount(), body.currency(), body.receipt(), body.notes());
    }

    @GetMapping("/orders/{id}")
    Order order(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String id) {
        return ledger.order(account(auth), id);
    }

    @GetMapping("/orders/{id}/payments")
    Collection<Payment> orderPayments(
            @RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String id) {
        List<Payment> items = ledger.paymentsOf(account(auth), id);
        return new Collection<>("collection", items.size(), items);
    }

    @GetMapping("/payments/{id}")
    Payment payment(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String id) {
        return ledger.payment(account(auth), id);
    }

    private Account account(String authorization) {
        if (authorization != null && authorization.startsWith("Basic ")) {
            String[] pair = new String(
                            Base64.getDecoder()
                                    .decode(authorization.substring(6).trim()),
                            StandardCharsets.UTF_8)
                    .split(":", 2);
            if (pair.length == 2) {
                var account = properties.account(pair[0]);
                if (account.isPresent() && account.get().keySecret().equals(pair[1])) {
                    return account.get();
                }
            }
        }
        throw new GatewayException(HttpStatus.UNAUTHORIZED, "Authentication failed");
    }
}
