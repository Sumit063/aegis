package dev.aegis.domain.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.domain.partner.PartnerGate.Repeat;
import dev.aegis.domain.partner.Stored.State;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PartnerGateTest {

    static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    static final Duration FRESHNESS = Duration.ofMinutes(5);
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    static PartnerRequest request(String method, String path, Instant signedAt, String hash) {
        return new PartnerRequest("acme", "req-1", method, path, signedAt, hash);
    }

    static PartnerRequest request(String path) {
        return request("POST", path, NOW, "h1");
    }

    @ParameterizedTest
    @CsvSource({
        "/recharges, recharges",
        "/recharges/r-1, recharges",
        "/status/r-1?verbose=true, status",
        "/bills/2026.11/x_y~z, bills"
    })
    void theOperationIsTheFirstPlainSegment(String path, String operation) {
        assertThat(request(path).operation()).contains(operation);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "/",
                "recharges",
                "/recharges/",
                "//recharges",
                "/recharges//x",
                "/recharges/../admin",
                "/../admin",
                "/./recharges",
                "/recharges/%2e%2e/admin",
                "/recharges%2F..",
                "/re charges",
                "/.hidden",
                "/recharges\\x"
            })
    void aPathThatIsNotPlainHasNoOperation(String path) {
        assertThat(request(path).operation()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "has space", "a/b", "ü", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
            })
    void requestIdsAreHeaderAndLogSafe(String id) {
        assertThatThrownBy(() -> new PartnerRequest("acme", id, "POST", "/x", NOW, "h"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRouteIsThePathWithoutItsQuery() {
        assertThat(request("/status/r-1?mobile=9000000001").route()).isEqualTo("/status/r-1");
        assertThat(request("/recharges").route()).isEqualTo("/recharges");
    }

    @Test
    void aFreshGrantedRequestIsAdmitted() {
        assertThat(PartnerGate.admit(request("/recharges"), Set.of("POST"), NOW, FRESHNESS))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {-301, 301, -86_400, 86_400})
    void aRequestSignedOutsideTheWindowEitherWayIsStale(long seconds) {
        PartnerRequest stale = request("POST", "/recharges", NOW.plusSeconds(seconds), "h");

        assertThat(PartnerGate.admit(stale, Set.of("POST"), NOW, FRESHNESS)).contains(Refusal.STALE);
    }

    @ParameterizedTest
    @ValueSource(longs = {-300, 300, 0})
    void theWindowEdgesAreFresh(long seconds) {
        PartnerRequest edge = request("POST", "/recharges", NOW.plusSeconds(seconds), "h");

        assertThat(PartnerGate.admit(edge, Set.of("POST"), NOW, FRESHNESS)).isEmpty();
    }

    @Test
    void anOperationOrMethodNotGrantedIsNotPermitted() {
        assertThat(PartnerGate.admit(request("/recharges"), Set.of(), NOW, FRESHNESS))
                .contains(Refusal.NOT_PERMITTED);
        assertThat(PartnerGate.admit(request("/recharges"), Set.of("GET"), NOW, FRESHNESS))
                .contains(Refusal.NOT_PERMITTED);
        assertThat(PartnerGate.admit(request("/recharges/../admin"), Set.of("POST"), NOW, FRESHNESS))
                .contains(Refusal.NOT_PERMITTED);
    }

    @Test
    void staleIsReportedBeforeScope() {
        PartnerRequest both = request("DELETE", "/admin", NOW.minusSeconds(3600), "h");

        assertThat(PartnerGate.admit(both, Set.of(), NOW, FRESHNESS)).isEqualTo(Optional.of(Refusal.STALE));
    }

    @ParameterizedTest
    @CsvSource({
        "ANSWERED, 1, REPLAY",
        "UNKNOWN, 1, OUTCOME_UNKNOWN",
        "IN_PROGRESS, 1, IN_PROGRESS",
        "IN_PROGRESS, 30, IN_PROGRESS",
        "IN_PROGRESS, 31, OUTCOME_UNKNOWN"
    })
    void aRepeatOfTheSameRequestGetsWhatTheFirstGot(State state, long secondsAgo, Repeat expected) {
        Stored stored = new Stored("h1", state, NOW.minusSeconds(secondsAgo));

        assertThat(PartnerGate.repeat(stored, request("/recharges"), NOW, TIMEOUT))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANSWERED", "UNKNOWN", "IN_PROGRESS"})
    void aDifferentRequestUnderAUsedIdIsReuse(State state) {
        Stored stored = new Stored("other-hash", state, NOW);

        assertThat(PartnerGate.repeat(stored, request("/recharges"), NOW, TIMEOUT))
                .isEqualTo(Repeat.REUSED);
    }
}
