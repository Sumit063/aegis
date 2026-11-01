package dev.aegis.lab.shop;

import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The storefront: place an order (creates the gateway order) and look at an order's state. */
@RestController
class ShopController {

    record PlaceOrder(long amount) {}

    record FlawSwitches(Set<Flaw> flaws) {}

    private final Shop shop;
    private final GatewayClient gateway;
    private final Flaws flaws;

    ShopController(Shop shop, GatewayClient gateway, Flaws flaws) {
        this.shop = shop;
        this.gateway = gateway;
        this.flaws = flaws;
    }

    @PostMapping("/orders")
    ShopOrder place(@RequestBody PlaceOrder body) {
        String id = shop.nextId();
        GatewayClient.GatewayOrder order = gateway.createOrder(id, body.amount());
        return shop.add(order.amount(), order.id(), id);
    }

    @GetMapping("/orders/{id}")
    ResponseEntity<ShopOrder> order(@PathVariable String id) {
        return ResponseEntity.of(shop.order(id));
    }

    @GetMapping("/flaws")
    FlawSwitches flaws() {
        return new FlawSwitches(flaws.current());
    }

    /** Lab control: switch flaws on for the next attack. */
    @PutMapping("/flaws")
    FlawSwitches switchFlaws(@RequestBody FlawSwitches body) {
        flaws.set(body.flaws());
        return new FlawSwitches(flaws.current());
    }
}
