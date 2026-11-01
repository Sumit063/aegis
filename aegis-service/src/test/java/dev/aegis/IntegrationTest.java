package dev.aegis;

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
 * Base of every integration test: the whole service against real PostgreSQL and Kafka, with a
 * Razorpay and an example connection for a shop (both pointing at {@link FakeProvider}), a second
 * application, and a clock tests can move. The containers start once per test run and the Spring context is
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
            "aegis.connections.example.adapter=example",
            "aegis.connections.example.application=shop",
            "aegis.connections.example.secrets.webhook[0]=" + IntegrationTest.EXAMPLE_WEBHOOK_SECRET,
            "aegis.connections.example.secrets.api-key[0]=" + IntegrationTest.EXAMPLE_API_KEY,
            "aegis.applications.other.api-key=" + IntegrationTest.OTHER_API_KEY,
            "aegis.applications.other.signing-secret=" + IntegrationTest.OTHER_API_KEY,
            "aegis.applications.other.forward-url=http://127.0.0.1:1/other"
        })
@Import(IntegrationTest.Clocks.class)
public abstract class IntegrationTest {

    /** Test-only secrets of the connections every integration test can use. */
    public static final String SHOP_API_KEY = "shop-api-key-for-integration-tests-0001";

    public static final String SHOP_SECRET = "shop-signing-secret-for-integration-0001";
    public static final String RAZORPAY_KEY_SECRET = "razorpay-key-secret-it-0001";
    public static final String RAZORPAY_WEBHOOK_SECRET = "razorpay-webhook-secret-it-01";
    public static final String EXAMPLE_WEBHOOK_SECRET = "example-webhook-secret-it-0001";
    public static final String EXAMPLE_API_KEY = "example-api-key-it-000000001";
    public static final String OTHER_API_KEY = "other-application-api-key-it-00000001";

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
                "aegis.connections.example.base-url", () -> FakeProvider.url().toString());
        registry.add(
                "aegis.applications.shop.forward-url",
                () -> FakeApplication.url().toString());
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
