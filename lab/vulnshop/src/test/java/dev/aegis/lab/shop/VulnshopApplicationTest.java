package dev.aegis.lab.shop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The shop starts with its default configuration and no flaws switched on. */
@SpringBootTest
class VulnshopApplicationTest {

    @Autowired
    Flaws flaws;

    @Test
    void startsCareful() {
        assertThat(flaws.current()).isEmpty();
    }
}
