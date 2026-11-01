import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pipeline benchmark against the running lab (KEEP=1 lab/run.sh, or lab/bench.sh):
 * provider → Ingress → Kafka → Checker → PostgreSQL → Forwarder → shop.
 *
 *   java lab/bench/Bench.java <payments> <concurrency>
 *
 * Mix per payment: one genuine signed webhook; 20 % also send it again (provider retry); 10 % add a
 * forged webhook. Reports acknowledgement latency (p50/p95/p99), throughput, end-to-end latency to
 * fulfilment, and checks correctness: every order fulfilled exactly once, no forgery accepted.
 */
public class Bench {

    static final String AEGIS = "http://127.0.0.1:8080";
    static final String SHOP = "http://127.0.0.1:8092";
    static final String SECRET = "lab-aegis-webhook-secret-not-real-02";
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final Pattern FIELD = Pattern.compile("\"(id|gateway_order_id|fulfilments|fulfilled_at)\":\"?([^\",}]*)");

    record Order(String shopId, String gatewayOrderId) {}

    public static void main(String[] args) throws Exception {
        int payments = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        put(SHOP + "/flaws", "{\"flaws\":[]}");

        System.out.printf("setup: %d orders through the Gateway API...%n", payments);
        List<Order> orders = Collections.synchronizedList(new ArrayList<>());
        run(payments, concurrency, i -> {
            Map<String, String> o = fields(post(SHOP + "/orders", "{\"amount\":49900}", Map.of()).body());
            orders.add(new Order(o.get("id"), o.get("gateway_order_id")));
        });

        ConcurrentLinkedQueue<Long> ackNanos = new ConcurrentLinkedQueue<>();
        Map<String, Instant> sentAt = new ConcurrentHashMap<>();
        Map<Integer, Integer> statuses = new ConcurrentHashMap<>();
        long start = System.nanoTime();
        run(orders.size(), concurrency, i -> {
            Order order = orders.get(i);
            String body = event(order.gatewayOrderId(), "pay_bench" + UUID.randomUUID().toString().substring(0, 12));
            String eventId = "evt_bench" + UUID.randomUUID();
            sentAt.put(order.shopId(), Instant.now());
            send(body, sign(body), eventId, ackNanos, statuses);
            double r = ThreadLocalRandom.current().nextDouble();
            if (r < 0.2) {
                send(body, sign(body), eventId, ackNanos, statuses); // provider retry
            }
            if (r > 0.9) {
                send(body, "forged" + i, "evt_forged" + i, ackNanos, statuses); // attacker
            }
        });
        double seconds = (System.nanoTime() - start) / 1e9;

        // Drain: wait until every order is fulfilled (or 120 s).
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        List<Long> e2eMillis = new ArrayList<>();
        int exactlyOnce = 0;
        int over = 0;
        for (Order order : orders) {
            Map<String, String> state;
            do {
                state = fields(get(SHOP + "/orders/" + order.shopId()));
                if (!"0".equals(state.get("fulfilments"))) {
                    break;
                }
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            int f = Integer.parseInt(state.getOrDefault("fulfilments", "0"));
            exactlyOnce += f == 1 ? 1 : 0;
            over += f > 1 ? 1 : 0;
            if (state.get("fulfilled_at") != null && !state.get("fulfilled_at").equals("null")) {
                e2eMillis.add(Duration.between(sentAt.get(order.shopId()), Instant.parse(state.get("fulfilled_at"))).toMillis());
            }
        }

        List<Long> acks = new ArrayList<>(ackNanos);
        Collections.sort(acks);
        Collections.sort(e2eMillis);
        System.out.println();
        System.out.println("| Metric | Value |");
        System.out.println("|---|---|");
        System.out.printf("| Payments | %d (requests sent: %d, concurrency %d) |%n", payments, acks.size(), concurrency);
        System.out.printf("| Ingress throughput | %.0f requests/s |%n", acks.size() / seconds);
        System.out.printf("| Acknowledgement p50 / p95 / p99 | %.1f / %.1f / %.1f ms |%n",
                pct(acks, 50) / 1e6, pct(acks, 95) / 1e6, pct(acks, 99) / 1e6);
        System.out.printf("| End-to-end to fulfilment p50 / p95 / p99 | %d / %d / %d ms |%n",
                pctL(e2eMillis, 50), pctL(e2eMillis, 95), pctL(e2eMillis, 99));
        System.out.printf("| Responses | %s |%n", statuses);
        System.out.printf("| Fulfilled exactly once | %d / %d (more than once: %d) |%n", exactlyOnce, payments, over);
        if (exactlyOnce != payments || over != 0) {
            System.exit(1);
        }
    }

    interface Step {
        void run(int i) throws Exception;
    }

    static void run(int n, int concurrency, Step step) throws Exception {
        Semaphore slots = new Semaphore(concurrency);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int index = i;
                slots.acquire();
                threads.submit(() -> {
                    try {
                        step.run(index);
                    } catch (Exception e) {
                        System.err.println("step " + index + ": " + e);
                    } finally {
                        slots.release();
                    }
                });
            }
        }
    }

    static void send(String body, String signature, String eventId, ConcurrentLinkedQueue<Long> acks, Map<Integer, Integer> statuses)
            throws Exception {
        long t = System.nanoTime();
        int status = post(AEGIS + "/in/razorpay", body, Map.of("X-Razorpay-Signature", signature, "X-Razorpay-Event-Id", eventId))
                .statusCode();
        acks.add(System.nanoTime() - t);
        statuses.merge(status, 1, Integer::sum);
    }

    static String event(String orderId, String paymentId) {
        return "{\"entity\":\"event\",\"event\":\"payment.captured\",\"contains\":[\"payment\"],\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"" + paymentId + "\",\"entity\":\"payment\",\"amount\":49900,\"currency\":\"INR\",\"status\":\"captured\","
                + "\"order_id\":\"" + orderId + "\"}}},\"created_at\":" + Instant.now().getEpochSecond() + "}";
    }

    static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    static HttpResponse<String> post(String url, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void put(String url, String body) throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
    }

    static String get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static Map<String, String> fields(String json) {
        Map<String, String> out = new java.util.HashMap<>();
        Matcher m = FIELD.matcher(json);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    static long pct(List<Long> sorted, int p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, sorted.size() * p / 100));
    }

    static long pctL(List<Long> sorted, int p) {
        return pct(sorted, p);
    }
}
