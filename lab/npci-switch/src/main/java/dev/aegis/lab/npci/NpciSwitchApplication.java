package dev.aegis.lab.npci;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * An NPCI-style switch for the lab (docs/design/NPCI.md): it verifies Aegis's signed BBPS and UPI
 * requests, answers them, and delivers signed asynchronous results with the behaviours that hurt in
 * production (deemed transactions, reversals, duplicates, redelivery until acknowledged). Its XML
 * signing is its own code on the JDK API, not Aegis's, so the two implementations check each other.
 * Never exposed outside the lab network.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NpciSwitchApplication {

    public static void main(String[] args) {
        SpringApplication.run(NpciSwitchApplication.class, args);
    }
}
