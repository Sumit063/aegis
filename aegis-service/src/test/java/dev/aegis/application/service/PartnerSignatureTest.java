package dev.aegis.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.provider.spi.Secret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PartnerSignatureTest {

    static final Secret CURRENT = new Secret("partner-secret-current-for-tests-00001");
    static final Secret PREVIOUS = new Secret("partner-secret-previous-for-tests-0001");
    static final byte[] BODY = "{\"amount\":49900}".getBytes(StandardCharsets.UTF_8);

    static byte[] canonical() {
        return PartnerSignature.canonical("POST", "/partner/recharges", "1767000000", "req-1", BODY);
    }

    @Test
    void theCanonicalStringNamesEverythingThatDecidesTheRequest() {
        assertThat(new String(canonical(), StandardCharsets.UTF_8))
                .isEqualTo("v1\nPOST\n/partner/recharges\n1767000000\nreq-1\n" + PartnerSignature.sha256Hex(BODY));
    }

    @Test
    void anySecretOfTheListVerifies() {
        String byPrevious = PartnerSignature.sign(PREVIOUS, canonical());

        assertThat(PartnerSignature.verify(List.of(CURRENT, PREVIOUS), byPrevious, canonical()))
                .isTrue();
        assertThat(PartnerSignature.verify(List.of(CURRENT), byPrevious, canonical()))
                .isFalse();
    }

    @Test
    void changingAnySignedPartFailsVerification() {
        String signature = PartnerSignature.sign(CURRENT, canonical());
        List<byte[]> tampered = List.of(
                PartnerSignature.canonical("GET", "/partner/recharges", "1767000000", "req-1", BODY),
                PartnerSignature.canonical("POST", "/partner/payouts", "1767000000", "req-1", BODY),
                PartnerSignature.canonical("POST", "/partner/recharges", "1767000001", "req-1", BODY),
                PartnerSignature.canonical("POST", "/partner/recharges", "1767000000", "req-2", BODY),
                PartnerSignature.canonical(
                        "POST", "/partner/recharges", "1767000000", "req-1", "{\"amount\":99900}".getBytes()));

        assertThat(tampered).noneMatch(data -> PartnerSignature.verify(List.of(CURRENT), signature, data));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v1=", "v2=abc", "abc", "v1=zz", "V1=00"})
    void malformedSignaturesFail(String header) {
        assertThat(PartnerSignature.verify(List.of(CURRENT), header, canonical()))
                .isFalse();
        assertThat(PartnerSignature.verify(List.of(CURRENT), null, canonical())).isFalse();
    }
}
