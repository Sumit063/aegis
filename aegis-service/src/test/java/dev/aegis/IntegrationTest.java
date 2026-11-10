package dev.aegis;

import dev.aegis.provider.spi.TestKeys;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base of every integration test: the whole service against real PostgreSQL and Kafka, with
 * Razorpay, Stripe, Cashfree, BBPS, UPI and example connections for a shop (all pointing at {@link FakeProvider}), a second
 * application, partners {@code acme} and {@code tiny} of the shop, and a clock tests can move. The containers start once per test run and the Spring context is
 * shared, so each IT adds seconds, not minutes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "aegis.applications.shop.api-key=" + IntegrationTest.SHOP_API_KEY,
            "aegis.applications.shop.signing-secret=" + IntegrationTest.SHOP_SECRET,
            "aegis.applications.shop.return-url=http://shop.test/return",
            "aegis.connections.razorpay.adapter=razorpay",
            "aegis.connections.razorpay.application=shop",
            "aegis.connections.razorpay.settings.key-id=rzp_test_it",
            "aegis.connections.razorpay.secrets.key-secret[0]=" + IntegrationTest.RAZORPAY_KEY_SECRET,
            "aegis.connections.razorpay.secrets.webhook[0]=" + IntegrationTest.RAZORPAY_WEBHOOK_SECRET,
            "aegis.connections.stripe.adapter=stripe",
            "aegis.connections.stripe.application=shop",
            "aegis.connections.stripe.secrets.secret-key[0]=" + IntegrationTest.STRIPE_SECRET_KEY,
            "aegis.connections.stripe.secrets.webhook[0]=" + IntegrationTest.STRIPE_WEBHOOK_SECRET,
            "aegis.connections.cashfree.adapter=cashfree",
            "aegis.connections.cashfree.application=shop",
            "aegis.connections.cashfree.settings.client-id=TEST-cashfree-it",
            "aegis.connections.cashfree.secrets.client-secret[0]=" + IntegrationTest.CASHFREE_CLIENT_SECRET,
            "aegis.connections.example.adapter=example",
            "aegis.connections.example.application=shop",
            "aegis.connections.example.secrets.webhook[0]=" + IntegrationTest.EXAMPLE_WEBHOOK_SECRET,
            "aegis.connections.example.secrets.api-key[0]=" + IntegrationTest.EXAMPLE_API_KEY,
            "aegis.applications.other.api-key=" + IntegrationTest.OTHER_API_KEY,
            "aegis.applications.other.signing-secret=" + IntegrationTest.OTHER_API_KEY,
            "aegis.applications.other.forward-url=http://127.0.0.1:1/other",
            "aegis.partners.acme.application=shop",
            "aegis.partners.acme.secrets[0]=" + IntegrationTest.ACME_SECRET,
            "aegis.partners.acme.secrets[1]=" + IntegrationTest.ACME_PREVIOUS_SECRET,
            "aegis.partners.acme.requests-per-minute=6000",
            "aegis.partners.acme.burst=500",
            "aegis.partners.acme.operations.recharges.methods=POST",
            "aegis.partners.acme.operations.status.methods=GET",
            "aegis.partners.tiny.application=shop",
            "aegis.partners.tiny.secrets[0]=" + IntegrationTest.TINY_SECRET,
            "aegis.partners.tiny.requests-per-minute=6000",
            "aegis.partners.tiny.burst=500",
            "aegis.partners.tiny.operations.recharges.methods=POST",
            "aegis.partners.tiny.operations.recharges.requests-per-minute=60",
            "aegis.partners.tiny.operations.recharges.burst=3",
            "aegis.partners.tiny.operations.status.methods=GET"
        })
@Import(IntegrationTest.Clocks.class)
public abstract class IntegrationTest {

    /** Test-only secrets of the connections every integration test can use. */
    public static final String SHOP_API_KEY = "shop-api-key-for-integration-tests-0001";

    public static final String SHOP_SECRET = "shop-signing-secret-for-integration-0001";
    public static final String RAZORPAY_KEY_SECRET = "razorpay-key-secret-it-0001";
    public static final String RAZORPAY_WEBHOOK_SECRET = "razorpay-webhook-secret-it-01";
    public static final String STRIPE_SECRET_KEY = "sk_test_stripe-secret-key-it-000001";
    public static final String STRIPE_WEBHOOK_SECRET = "whsec_stripe-webhook-secret-it-01";
    public static final String CASHFREE_CLIENT_SECRET = "cashfree-client-secret-it-00000001";
    public static final String EXAMPLE_WEBHOOK_SECRET = "example-webhook-secret-it-0001";
    public static final String EXAMPLE_API_KEY = "example-api-key-it-000000001";
    public static final String OTHER_API_KEY = "other-application-api-key-it-00000001";
    public static final String ACME_SECRET = "acme-partner-signing-secret-it-000000001";
    public static final String ACME_PREVIOUS_SECRET = "acme-partner-previous-secret-it-0000001";
    public static final String TINY_SECRET = "tiny-partner-signing-secret-it-000000001";
    /** The {@code local} profile's admin key. */
    public static final String ADMIN_KEY = "local-only-admin-key-000000000000";

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add(
                "aegis.connections.razorpay.base-url", () -> FakeProvider.url().toString());
        registry.add(
                "aegis.connections.stripe.base-url", () -> FakeProvider.url().toString());
        registry.add("aegis.connections.cashfree.base-url", () -> FakeProvider.url() + "/pg");
        registry.add(
                "aegis.connections.example.base-url", () -> FakeProvider.url().toString());
        // NPCI-style connections: keys generated per run, trusted switch key and Aegis's signing key.
        for (String npci : List.of("bbps", "upi")) {
            String prefix = "aegis.connections." + npci;
            registry.add(prefix + ".adapter", () -> npci);
            registry.add(prefix + ".application", () -> "shop");
            registry.add(prefix + ".base-url", () -> FakeProvider.url().toString());
            registry.add(prefix + ".secrets.signing-key[0]", () -> TestKeys.privateKey(TestKeys.AEGIS));
            registry.add(prefix + ".secrets.switch-certificate[0]", () -> TestKeys.publicKey(TestKeys.CURRENT));
        }
        registry.add(
                "aegis.applications.shop.forward-url",
                () -> FakeApplication.url().toString());
        registry.add(
                "aegis.applications.shop.partner-url",
                () -> FakeApplication.partnerUrl().toString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }
}
