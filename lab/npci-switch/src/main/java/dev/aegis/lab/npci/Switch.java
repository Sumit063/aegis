package dev.aegis.lab.npci;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The switch's state: bills, transactions and what happens to each next payment. Results are
 * delivered in the background after {@code npci.delay}; an unacknowledged one is redelivered once a
 * second for {@code npci.redeliver}, unchanged, as a switch waits for its {@code Ack}.
 */
@Component
class Switch {

    enum Network {
        BBPS,
        UPI
    }

    /** What happens to a payment after its request is acknowledged. */
    enum Script {
        SUCCESS,
        FAILURE,
        DEEMED_THEN_SUCCESS,
        DEEMED_THEN_FAILURE,
        /** UPI: debited, then the money is returned. BBPS has no reversal: deemed, then failure. */
        DEEMED_THEN_REVERSAL,
        /** A misbehaving switch: success, then a contradicting failure. */
        SUCCESS_THEN_FAILURE,
        /** No result at all: only a status check can tell. */
        SILENT
    }

    /** The script for one payment, how many extra copies of each result, and whether its amount is wrong. */
    record Plan(Script script, int duplicates, boolean wrongAmount) {
        static final Plan DEFAULT = new Plan(Script.SUCCESS, 0, false);
    }

    /** One payment; {@code status} as a status check reports it. */
    static final class Txn {
        final Network network;
        final String ref;
        final String id;
        final long amount;
        final Plan plan;
        volatile String status = "PENDING";
        volatile String lastDelivered;
        final AtomicInteger deliveries = new AtomicInteger();

        Txn(Network network, String ref, String id, long amount, Plan plan) {
            this.network = network;
            this.ref = ref;
            this.id = id;
            this.amount = amount;
            this.plan = plan;
        }
    }

    private final SwitchProperties properties;
    private final PrivateKey switchKey;
    private final PublicKey aegisKey;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Queue<Plan> next = new ConcurrentLinkedQueue<>();
    private final Map<String, Txn> txns = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> received = new ConcurrentHashMap<>();
    private final AtomicLong billAmount = new AtomicLong(84_000);

    Switch(SwitchProperties properties) {
        this.properties = properties;
        this.switchKey = XmlDsig.privateKey(properties.switchKey());
        this.aegisKey = XmlDsig.publicKey(properties.aegisKey());
    }

    boolean fromAegis(String xml) {
        return XmlDsig.verify(xml, aegisKey);
    }

    String signed(String xml) {
        return XmlDsig.sign(xml, switchKey);
    }

    PrivateKey switchKey() {
        return switchKey;
    }

    /** The switch's public key, encoded: what an HMAC key-confusion attack keys its MAC with. */
    byte[] publicKeyBytes() {
        try {
            RSAPrivateCrtKey key = (RSAPrivateCrtKey) switchKey;
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()))
                    .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    void count(String api) {
        received.computeIfAbsent(api, k -> new AtomicInteger()).incrementAndGet();
    }

    Map<String, Integer> received() {
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        received.forEach((api, n) -> counts.put(api, n.get()));
        return counts;
    }

    void plan(Plan plan) {
        next.add(plan);
    }

    void billAmount(long amount) {
        billAmount.set(amount);
    }

    long billAmount() {
        return billAmount.get();
    }

    Optional<Txn> txn(String id) {
        return Optional.ofNullable(txns.get(id));
    }

    /** Registers an acknowledged payment and plays its script in the background. */
    void pay(Network network, String ref, String id, long amount) {
        Plan plan = Optional.ofNullable(next.poll()).orElse(Plan.DEFAULT);
        Txn txn = new Txn(network, ref, id, amount, plan);
        if (txns.putIfAbsent(id, txn) != null) {
            return;
        }
        Thread.ofVirtual().start(() -> play(txn));
    }

    private void play(Txn txn) {
        String last = switch (txn.plan.script()) {
            case SUCCESS -> "SUCCESS";
            case FAILURE -> "FAILURE";
            case DEEMED_THEN_SUCCESS -> "SUCCESS";
            case DEEMED_THEN_FAILURE -> "FAILURE";
            case DEEMED_THEN_REVERSAL -> txn.network == Network.UPI ? "REVERSED" : "FAILURE";
            case SUCCESS_THEN_FAILURE -> "FAILURE";
            case SILENT -> null;
        };
        boolean deemedFirst = txn.plan.script().name().startsWith("DEEMED");
        pause(properties.delay());
        if (deemedFirst) {
            send(txn, "PENDING");
            pause(properties.delay());
        }
        if (txn.plan.script() == Script.SUCCESS_THEN_FAILURE) {
            send(txn, "SUCCESS");
            pause(properties.delay());
        }
        if (last != null) {
            send(txn, last);
        }
    }

    /** A result as the switch sends it now, signed. */
    String result(Txn txn, String status, Instant at) {
        long amount = txn.plan.wrongAmount() ? 1 : txn.amount;
        return signed(
                switch (txn.network) {
                    case BBPS -> Messages.billPaymentResponse(txn.ref, txn.id, status, amount, at);
                    case UPI ->
                        "REVERSED".equals(status)
                                ? Messages.respPay(txn.ref, "RV" + txn.id, "REVERSAL", txn.id, "SUCCESS", amount, at)
                                : Messages.respPay(
                                        txn.ref,
                                        txn.id,
                                        "PAY",
                                        null,
                                        "PENDING".equals(status) ? "DEEMED" : status,
                                        amount,
                                        at);
                });
    }

    private void send(Txn txn, String status) {
        txn.status = status;
        String message = result(txn, status, Instant.now());
        txn.lastDelivered = message;
        for (int copy = 0; copy <= txn.plan.duplicates(); copy++) {
            deliverUntilAcknowledged(txn, message);
        }
    }

    private void deliverUntilAcknowledged(Txn txn, String message) {
        Instant until = Instant.now().plus(properties.redeliver());
        while (true) {
            int status = deliver(txn.network, message);
            txn.deliveries.incrementAndGet();
            if ((status >= 200 && status < 300) || !Instant.now().isBefore(until)) {
                return;
            }
            pause(Duration.ofSeconds(1));
        }
    }

    /** Sends {@code xml} to the network's callback; the receiver's HTTP status, 0 if unreachable. */
    int deliver(Network network, String xml) {
        URI callback = network == Network.BBPS ? properties.bbpsCallback() : properties.upiCallback();
        HttpRequest request = HttpRequest.newBuilder(callback)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(xml))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (java.io.IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
