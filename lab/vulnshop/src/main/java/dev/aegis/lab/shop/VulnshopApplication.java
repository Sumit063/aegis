package dev.aegis.lab.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A small merchant: orders paid through a Razorpay-style gateway and fulfilled when payment is
 * confirmed. Each known integration flaw can be switched on alone ({@link Flaw}). Lab only.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VulnshopApplication {

    public static void main(String[] args) {
        SpringApplication.run(VulnshopApplication.class, args);
    }
}
