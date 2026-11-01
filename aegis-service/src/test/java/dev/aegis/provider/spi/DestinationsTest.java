package dev.aegis.provider.spi;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SSRF guard on configured destinations (review item 24). */
class DestinationsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://169.254.169.254/latest/meta-data/",
                "http://[fe80::1]/x",
                "http://metadata.google.internal/computeMetadata/v1/",
                "http://0.0.0.0:8080/",
                "ftp://shop.example/x",
                "file:///etc/passwd"
            })
    void refusesMetadataLinkLocalAndOtherSchemes(String uri) {
        assertThatThrownBy(() -> Destinations.check(URI.create(uri), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://shop.example/aegis", "http://10.0.0.5/aegis", "http://127.0.0.1:8092/aegis"})
    void allowsPublicAndPrivateMerchants(String uri) {
        assertThatCode(() -> Destinations.check(URI.create(uri), true)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://shop.example/aegis", "http://10.0.0.5/aegis"})
    void productionRequiresHttps(String uri) {
        assertThatThrownBy(() -> Destinations.check(URI.create(uri), false)).hasMessageContaining("https");
    }
}
