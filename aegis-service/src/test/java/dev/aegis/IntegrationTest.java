package dev.aegis;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base of every integration test: the whole service against real PostgreSQL and Kafka, with a
 * Razorpay connection for a shop. The containers start once per test run and the Spring context is
 * shared, so each IT adds seconds, not minutes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "aegis.applications.shop.api-key=" + IntegrationTest.SHOP_API_KEY,
            "aegis.applications.shop.signing-secret=" + IntegrationTest.SHOP_SECRET,
            "aegis.applications.shop.forward-url=http://127.0.0.1:1/aegis/messages",
            "aegis.applications.shop.return-url=http://shop.test/return",
            "aegis.connections.razorpay.adapter=razorpay",
            "aegis.connections.razorpay.application=shop",
            "aegis.connections.razorpay.base-url=http://127.0.0.1:1",
            "aegis.connections.razorpay.settings.key-id=rzp_test_it",
            "aegis.connections.razorpay.secrets.key-secret[0]=" + IntegrationTest.RAZORPAY_KEY_SECRET,
            "aegis.connections.razorpay.secrets.webhook[0]=" + IntegrationTest.RAZORPAY_WEBHOOK_SECRET
        })
public abstract class IntegrationTest {

    /** Test-only secrets of the connections every integration test can use. */
    public static final String SHOP_API_KEY = "shop-api-key-for-integration-tests-0001";

    public static final String SHOP_SECRET = "shop-signing-secret-for-integration-0001";
    public static final String RAZORPAY_KEY_SECRET = "razorpay-key-secret-it-0001";
    public static final String RAZORPAY_WEBHOOK_SECRET = "razorpay-webhook-secret-it-01";

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

    static {
        POSTGRES.start();
        KAFKA.start();
    }
}
