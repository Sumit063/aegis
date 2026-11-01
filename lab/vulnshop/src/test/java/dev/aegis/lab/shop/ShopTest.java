package dev.aegis.lab.shop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.lab.shop.PaymentEvent.Channel;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Each flaw removes exactly one protection; with none on, every attack is refused. */
class ShopTest {

    private static Shop shop(Flaw... flaws) {
        Shop shop = new Shop(new ShopProperties(null, null, null, null, null, Set.of(flaws), Duration.ofDays(7)));
        shop.add(1_000_000, "order_big", "SHOP-BIG");
        shop.add(1_000, "order_small", "SHOP-SMALL");
        return shop;
    }

    private static PaymentEvent webhook(
            String eventId, String payment, String order, String hint, long amount, boolean ok) {
        return new PaymentEvent(Channel.WEBHOOK, eventId, payment, order, hint, amount, ok, Instant.now());
    }

    private static PaymentEvent callback(String payment, String order, String hint) {
        return new PaymentEvent(Channel.CALLBACK, null, payment, order, hint, null, true, null);
    }

    @Test
    void genuinePaymentFulfilsOnce() {
        Shop shop = shop();
        assertThat(shop.apply(webhook("evt_1", "pay_1", "order_big", null, 1_000_000, true)))
                .isEqualTo("fulfilled SHOP-BIG");
        assertThat(shop.order("SHOP-BIG").orElseThrow().fulfilments()).isEqualTo(1);
    }

    @Test
    void f3TrustsTheNotesAndSkipsTheAmount() {
        PaymentEvent cheapForBig = webhook("evt_1", "pay_1", null, "SHOP-BIG", 1_000, true);
        assertThat(shop().apply(cheapForBig)).startsWith("ignored");
        assertThat(shop(Flaw.F3).apply(cheapForBig)).isEqualTo("fulfilled SHOP-BIG");
    }

    @Test
    void f5RepeatsTheSameEvent() {
        Shop safe = shop();
        Shop flawed = shop(Flaw.F5);
        for (int i = 0; i < 5; i++) {
            safe.apply(webhook("evt_1", "pay_1", "order_big", null, 1_000_000, true));
            flawed.apply(webhook("evt_1", "pay_1", "order_big", null, 1_000_000, true));
        }
        assertThat(safe.order("SHOP-BIG").orElseThrow().fulfilments()).isEqualTo(1);
        assertThat(flawed.order("SHOP-BIG").orElseThrow().fulfilments()).isEqualTo(5);
    }

    @Test
    void f6LetsALateFailureCancelAPaidOrder() {
        for (Flaw[] flaws : new Flaw[][] {{}, {Flaw.F6}}) {
            Shop shop = shop(flaws);
            shop.apply(webhook("evt_2", "pay_2", "order_big", null, 1_000_000, true));
            shop.apply(webhook("evt_1", "pay_1", "order_big", null, 1_000_000, false));
            assertThat(shop.order("SHOP-BIG").orElseThrow().status())
                    .isEqualTo(flaws.length == 0 ? ShopOrder.Status.PAID : ShopOrder.Status.CANCELLED);
        }
    }

    @Test
    void f8FulfilsTheOrderInTheUrl() {
        PaymentEvent smallSignedForBig = callback("pay_1", "order_small", "SHOP-BIG");
        Shop safe = shop();
        safe.apply(smallSignedForBig);
        assertThat(safe.order("SHOP-BIG").orElseThrow().fulfilments()).isZero();
        Shop flawed = shop(Flaw.F8);
        flawed.apply(smallSignedForBig);
        assertThat(flawed.order("SHOP-BIG").orElseThrow().fulfilments()).isEqualTo(1);
    }

    @Test
    void f9FulfilsOnBothChannels() {
        for (Flaw[] flaws : new Flaw[][] {{}, {Flaw.F9}}) {
            Shop shop = shop(flaws);
            shop.apply(callback("pay_1", "order_big", "SHOP-BIG"));
            shop.apply(webhook("evt_1", "pay_1", "order_big", null, 1_000_000, true));
            assertThat(shop.order("SHOP-BIG").orElseThrow().fulfilments()).isEqualTo(flaws.length == 0 ? 1 : 2);
        }
    }

    @Test
    void f10AcceptsAnOldEventOnceItsIdIsForgotten() {
        PaymentEvent old = new PaymentEvent(
                Channel.WEBHOOK,
                "evt_old",
                "pay_1",
                "order_big",
                null,
                1_000_000L,
                true,
                Instant.now().minus(Duration.ofDays(30)));
        assertThat(shop().apply(old)).isEqualTo("ignored: too old");
        assertThat(shop(Flaw.F10).apply(old)).isEqualTo("fulfilled SHOP-BIG");
    }
}
