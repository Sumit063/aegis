package dev.aegis.lab.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/** Direct mode runs anything with the key; behind Aegis only what Aegis signed runs. */
class PartnerApiControllerTest {

    static final String KEY = "partner-key-for-tests-only-000000001";
    static final String AEGIS_SECRET = "aegis-secret-for-tests-only-0000001";
    static final String BODY = "{\"mobile\":\"9000000001\",\"amount\":49900}";

    final JsonMapper json = JsonMapper.builder().build();

    static MockHttpServletRequest post(Map<String, String> headers) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/partner-api/recharges");
        headers.forEach(request::addHeader);
        return request;
    }

    @Test
    void directModeRunsEveryCopyOfARequestThatCarriesTheKey() {
        PartnerApiController api = new PartnerApiController(new PartnerApiProperties("direct", KEY, null), json);
        Map<String, String> headers = Map.of("X-Api-Key", KEY, "X-Request-Id", "r-1");

        api.recharge(BODY, post(headers));
        api.recharge(BODY, post(headers));

        assertThat(api.recharge(BODY, post(Map.of("X-Api-Key", "wrong")))
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(api.executions()).containsEntry("byRequest", Map.of("r-1", 2));
    }

    @Test
    void behindAegisOnlyWhatAegisSignedRuns() {
        PartnerApiController api =
                new PartnerApiController(new PartnerApiProperties("aegis", null, AEGIS_SECRET), json);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String signature = "v1=" + Hmac.sha256Hex(AEGIS_SECRET, ts + ".acme.r-1.POST./recharges." + BODY);
        Map<String, String> signed = Map.of(
                "X-Aegis-Timestamp",
                ts,
                "X-Aegis-Signature",
                signature,
                "X-Aegis-Partner",
                "acme",
                "X-Aegis-Request-Id",
                "r-1");

        assertThat(api.recharge(BODY, post(signed)).getStatusCode().value()).isEqualTo(201);
        assertThat(api.recharge(BODY.replace("49900", "99900"), post(signed))
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(api.recharge(BODY, post(Map.of("X-Api-Key", KEY)))
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(api.executions()).containsEntry("rechargedPaise", 49900L);
    }
}
