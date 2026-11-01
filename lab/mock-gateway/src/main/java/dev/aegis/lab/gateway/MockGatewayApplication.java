package dev.aegis.lab.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A Razorpay-compatible provider for the Exploit Lab. It holds the real state of every order and
 * payment, signs webhooks and checkout results exactly like Razorpay, and lets tests act as the
 * customer. Never exposed outside the lab network.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MockGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockGatewayApplication.class, args);
    }
}
