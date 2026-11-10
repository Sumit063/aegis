import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Partner API benchmark against the running lab (KEEP=1 lab/partners.sh): partner → Aegis (verify,
 * Redis rates, request-ID claim in PostgreSQL) → shop → answer recorded. Requests alternate between
 * the two Aegis instances.
 *
 *   java lab/bench/PartnerBench.java <requests> <concurrency> [copies]
 *
 * With copies > 1 every request is sent that many times at once (a retry storm). Reports latency
 * (p50/p95/p99) and throughput, then checks with the shop that every request ran exactly once.
 */
public class PartnerBench {

    static final List<String> AEGIS = List.of("http://127.0.0.1:8080", "http://127.0.0.1:8082");
    static final String SHOP = "http://127.0.0.1:8092";
    static final String SECRET = "lab-acme-partner-secret-not-real-000000001";
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) throws Exception {
        int requests = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        int copies = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        Map<Integer, Integer> statuses = new ConcurrentHashMap<>();
        Semaphore inFlight = new Semaphore(concurrency);

        long started = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                String id = "B" + i + "-" + run;
                for (int c = 0; c < copies; c++) {
                    String base = AEGIS.get((i + c) % 2);
                    inFlight.acquire();
                    pool.submit(() -> {
                        try {
                            long t = System.nanoTime();
                            int status = send(base, id);
                            latencies.add(System.nanoTime() - t);
                            statuses.merge(status, 1, Integer::sum);
                        } catch (Exception e) {
                            statuses.merge(-1, 1, Integer::sum);
                        } finally {
                            inFlight.release();
                        }
                    });
                }
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        String executions = HTTP.send(
                        HttpRequest.newBuilder(URI.create(SHOP + "/lab/partner-executions")).build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
        long once = 0;
        long more = 0;
        for (int i = 0; i < requests; i++) {
            String key = "\"B" + i + "-" + run + "\":";
            int at = executions.indexOf(key);
            if (at < 0) {
                continue;
            }
            int n = Integer.parseInt(executions.substring(at + key.length()).split("[,}]")[0].trim());
            if (n == 1) {
                once++;
            } else {
                more++;
            }
        }
        System.out.printf(
                "%d requests (%d × %d) at concurrency %d: %.0f req/s, latency p50 %.1f / p95 %.1f / p99 %.1f ms%n",
                sorted.size(),
                requests,
                copies,
                concurrency,
                sorted.size() / seconds,
                ms(sorted, 0.50),
                ms(sorted, 0.95),
                ms(sorted, 0.99));
        System.out.printf("statuses %s; executed once %d/%d, more than once %d%n", statuses, once, requests, more);
        if (once != requests || more != 0) {
            System.exit(1);
        }
    }

    static int send(String base, String id) throws Exception {
        String body = "{\"mobile\":\"9000000001\",\"amount\":49900}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String digest = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        String canonical = String.join("\n", "v1", "POST", "/partner/recharges", ts, id, digest);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        return HTTP.send(
                        HttpRequest.newBuilder(URI.create(base + "/partner/recharges"))
                                .header("X-Partner-Id", "acme")
                                .header("X-Request-Id", id)
                                .header("X-Timestamp", ts)
                                .header("X-Signature", "v1=" + signature)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    static double ms(List<Long> sorted, double q) {
        return sorted.isEmpty() ? 0 : sorted.get((int) Math.min(sorted.size() - 1, q * sorted.size())) / 1e6;
    }
}
