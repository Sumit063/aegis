import dev.aegis.provider.spi.Xml;
import dev.aegis.provider.spi.XmlSignature;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.w3c.dom.Document;

/**
 * What Aegis's XML signatures cost per NPCI message (M3): signing a request, and parsing plus verifying
 * a result, on one core and on all cores. RSA-2048, the D33 profile.
 *   ./mvnw -q -DskipTests package -pl aegis-service && java -cp aegis-service/target/classes lab/bench/XmlBench.java
 */
public class XmlBench {

    static final String RESPONSE = """
            <bbps:BillPaymentResponse xmlns:bbps="http://bbps.org/schema">\
            <Head ver="1.0" ts="2026-11-10T10:00:00+05:30" refId="R1" msgId="M1"/>\
            <Reason responseCode="000" responseReason="Successful" approvalRefNum="AB1"/>\
            <Txn txnReferenceId="T1"/><Amount><Amt amount="84000" currency="356"/></Amount>\
            </bbps:BillPaymentResponse>""";

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 5_000;
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
        Document signed = Xml.parse(body);
        XmlSignature.sign(signed, key.getPrivate());
        byte[] wire = Xml.write(signed);
        List<java.security.PublicKey> trusted = List.of(key.getPublic());

        Runnable sign = () -> XmlSignature.sign(Xml.parse(body), key.getPrivate());
        Runnable verify = () -> {
            if (!XmlSignature.verify(Xml.parse(wire), trusted)) {
                throw new IllegalStateException("did not verify");
            }
        };
        for (int i = 0; i < 2_000; i++) {
            sign.run();
            verify.run();
        }
        report("sign a request (parse, sign, 1 core)", time(sign, n));
        report("verify a message (parse, verify, 1 core)", time(verify, n));
        int cores = Runtime.getRuntime().availableProcessors();
        long start = System.nanoTime();
        IntStream.range(0, n * 4).parallel().forEach(i -> verify.run());
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("verify on %d cores: %,.0f messages/s%n", cores, n * 4 / seconds);
    }

    static long[] time(Runnable task, int n) {
        long[] micros = new long[n];
        for (int i = 0; i < n; i++) {
            long start = System.nanoTime();
            task.run();
            micros[i] = (System.nanoTime() - start) / 1_000;
        }
        Arrays.sort(micros);
        return micros;
    }

    static void report(String what, long[] micros) {
        double mean = Arrays.stream(micros).average().orElse(0);
        System.out.printf(
                "%-44s p50 %4d µs  p99 %5d µs  %,6.0f/s%n",
                what, micros[micros.length / 2], micros[micros.length * 99 / 100], 1e6 / mean);
    }
}
