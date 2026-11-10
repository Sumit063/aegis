package dev.aegis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.config.AegisProperties.ApplicationProperties;
import dev.aegis.config.AegisProperties.ConnectionProperties;
import dev.aegis.config.AegisProperties.OperationProperties;
import dev.aegis.config.AegisProperties.PartnerProperties;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AegisPropertiesTest {

    static final String KEY_SECRET = "key-secret-test-only-000000001";
    static final String WEBHOOK_SECRET = "webhook-secret-test-only-00001";
    static final String APP_SECRET = "application-secret-for-tests-only-0000000001";

    static ApplicationProperties shop() {
        return new ApplicationProperties(
                APP_SECRET,
                URI.create("http://shop/aegis"),
                URI.create("http://shop/return"),
                null,
                APP_SECRET,
                null,
                URI.create("http://shop/partner-api"));
    }

    static ConnectionProperties razorpay(String... webhookSecrets) {
        return new ConnectionProperties(
                "razorpay",
                "shop",
                URI.create("https://api.razorpay.com"),
                Map.of("key-id", "rzp_test_x"),
                Map.of("key-secret", List.of(KEY_SECRET), "webhook", Arrays.asList(webhookSecrets)));
    }

    static AegisProperties properties(ConnectionProperties connection) {
        return new AegisProperties(
                DeploymentEnvironment.LOCAL,
                Set.of(Role.INGRESS),
                Map.of("razorpay", connection),
                Map.of("shop", shop()),
                null);
    }

    @Test
    void reportsWhichRolesThisProcessRuns() {
        AegisProperties properties =
                new AegisProperties(DeploymentEnvironment.LOCAL, Set.of(Role.INGRESS, Role.CHECKER), null, null, null);

        assertThat(properties.runs(Role.INGRESS)).isTrue();
        assertThat(properties.runs(Role.ADMIN)).isFalse();
        assertThat(properties.connections()).isEmpty();
    }

    @Test
    void refusesToStartWithoutRoles() {
        assertThatThrownBy(() -> new AegisProperties(DeploymentEnvironment.LOCAL, Set.of(), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aegis.roles");
        assertThatThrownBy(() -> new AegisProperties(DeploymentEnvironment.LOCAL, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutAnEnvironment() {
        assertThatThrownBy(() -> new AegisProperties(null, Set.of(Role.ADMIN), null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("profile");
    }

    @Test
    void onlyProdIsProduction() {
        assertThat(DeploymentEnvironment.PROD.isProduction()).isTrue();
        assertThat(DeploymentEnvironment.STAGING.isProduction()).isFalse();
    }

    @Test
    void dropsAnEmptyPreviousSecretSlot() {
        AegisProperties properties = properties(razorpay(WEBHOOK_SECRET, "", null));

        assertThat(properties.connections().get("razorpay").secrets().get("webhook"))
                .containsExactly(WEBHOOK_SECRET);
    }

    /** T18 and CVE-2026-41432: an empty secret must never become an empty HMAC key. */
    @Test
    void refusesAnEmptySecret() {
        assertThatThrownBy(() -> properties(razorpay("", " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("aegis.connections.razorpay.secrets.webhook is empty");
    }

    @Test
    void refusesAShortSecretWithoutPrintingIt() {
        assertThatThrownBy(() -> properties(razorpay("short-secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than 16")
                .hasMessageNotContaining("short-secret");
    }

    @Test
    void refusesAConnectionForAnUnknownApplication() {
        ConnectionProperties orphan =
                new ConnectionProperties("razorpay", "nobody", null, null, Map.of("webhook", List.of(WEBHOOK_SECRET)));

        assertThatThrownBy(() -> properties(orphan)).hasMessageContaining("unknown application");
    }

    @Test
    void refusesAShortApplicationSecret() {
        ApplicationProperties weak =
                new ApplicationProperties("too-short", URI.create("http://shop"), null, null, APP_SECRET, null, null);

        assertThatThrownBy(() -> new AegisProperties(
                        DeploymentEnvironment.LOCAL, Set.of(Role.ADMIN), null, Map.of("shop", weak), null))
                .hasMessage("aegis.applications.shop.api-key is shorter than 32 characters");
    }

    @Test
    void neverPrintsSecrets() {
        AegisProperties properties = properties(razorpay(WEBHOOK_SECRET));

        assertThat(properties.toString()).doesNotContain(KEY_SECRET, WEBHOOK_SECRET, APP_SECRET);
    }

    @Test
    void bindsFromConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(Binding.class)
                .withPropertyValues(
                        "aegis.environment=local",
                        "aegis.roles=ingress",
                        "aegis.connections.razorpay.adapter=razorpay",
                        "aegis.connections.razorpay.application=shop",
                        "aegis.connections.razorpay.base-url=https://api.razorpay.com",
                        "aegis.connections.razorpay.settings.key-id=rzp_test_x",
                        "aegis.connections.razorpay.secrets.webhook[0]=" + WEBHOOK_SECRET,
                        "aegis.connections.razorpay.secrets.webhook[1]=",
                        "aegis.applications.shop.api-key=" + APP_SECRET,
                        "aegis.applications.shop.signing-secret=" + APP_SECRET,
                        "aegis.applications.shop.forward-url=http://shop/aegis")
                .run(context -> {
                    AegisProperties bound = context.getBean(AegisProperties.class);
                    ConnectionProperties connection = bound.connections().get("razorpay");
                    assertThat(connection.settings()).containsEntry("key-id", "rzp_test_x");
                    assertThat(connection.secrets().get("webhook")).containsExactly(WEBHOOK_SECRET);
                    assertThat(bound.applications().get("shop").forwardUrl()).hasToString("http://shop/aegis");
                });
    }

    static final String PARTNER_SECRET = "partner-signing-secret-for-tests-000001";

    static PartnerProperties acme(Map<String, OperationProperties> operations) {
        return new PartnerProperties("shop", List.of(PARTNER_SECRET, ""), null, 600, 50, operations);
    }

    static AegisProperties withPartner(ApplicationProperties shop, PartnerProperties partner) {
        return new AegisProperties(
                DeploymentEnvironment.LOCAL, Set.of(Role.PARTNER), null, Map.of("shop", shop), Map.of("acme", partner));
    }

    @Test
    void aPartnerIsValidatedAndEmptyPreviousSecretsAreDropped() {
        AegisProperties properties =
                withPartner(shop(), acme(Map.of("recharges", new OperationProperties(List.of("POST"), 60, 10))));

        assertThat(properties.partners().get("acme").secrets()).containsExactly(PARTNER_SECRET);
        assertThat(properties.toString()).doesNotContain(PARTNER_SECRET);
    }

    @Test
    void aPartnerOfAnApplicationWithoutAPartnerApiIsRefused() {
        ApplicationProperties noPartnerApi = new ApplicationProperties(
                APP_SECRET, URI.create("http://shop/aegis"), null, null, APP_SECRET, null, null);

        assertThatThrownBy(() -> withPartner(
                        noPartnerApi, acme(Map.of("recharges", new OperationProperties(List.of("POST"), null, null)))))
                .hasMessage("aegis.applications.shop.partner-url must be set: aegis.partners.acme calls it");
    }

    @Test
    void aPartnerConfigurationThatIsIncompleteOrWeakStopsStartup() {
        Map<String, OperationProperties> post =
                Map.of("recharges", new OperationProperties(List.of("POST"), null, null));
        Map<PartnerProperties, String> broken = Map.of(
                new PartnerProperties("nope", List.of(PARTNER_SECRET), null, 600, 50, post),
                "aegis.partners.acme.application names an unknown application",
                new PartnerProperties("shop", List.of(""), null, 600, 50, post),
                "aegis.partners.acme.secrets is empty",
                new PartnerProperties("shop", List.of("short"), null, 600, 50, post),
                "aegis.partners.acme.secrets is shorter than 32 characters",
                new PartnerProperties("shop", List.of(PARTNER_SECRET), null, 0, 50, post),
                "aegis.partners.acme.requests-per-minute must be a positive number",
                new PartnerProperties("shop", List.of(PARTNER_SECRET), null, 600, null, post),
                "aegis.partners.acme.burst must be a positive number",
                acme(Map.of()),
                "aegis.partners.acme.operations must grant at least one operation",
                acme(Map.of("Recharges", new OperationProperties(List.of("POST"), null, null))),
                "aegis.partners.acme.operations.Recharges is not a valid operation name ([a-z0-9][a-z0-9-]{0,31})",
                acme(Map.of("recharges", new OperationProperties(List.of("TRACE"), null, null))),
                "aegis.partners.acme.operations.recharges.methods must name some of",
                acme(Map.of("recharges", new OperationProperties(List.of("POST"), 60, null))),
                "aegis.partners.acme.operations.recharges needs both requests-per-minute and burst, or neither");

        broken.forEach((partner, message) -> assertThatThrownBy(() -> withPartner(shop(), partner))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(message));
    }

    @EnableConfigurationProperties(AegisProperties.class)
    static class Binding {}
}
