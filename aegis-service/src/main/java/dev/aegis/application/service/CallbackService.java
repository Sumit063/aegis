package dev.aegis.application.service;

import dev.aegis.application.port.in.CheckMessageUseCase;
import dev.aegis.application.port.in.HandleCallbackUseCase;
import dev.aegis.application.port.in.UnknownConnectionException;
import dev.aegis.application.port.out.Application;
import dev.aegis.application.port.out.DirectoryPort;
import dev.aegis.application.port.out.EvidenceCipherPort;
import dev.aegis.application.port.out.IngressRecord;
import dev.aegis.provider.spi.Channel;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.ProviderAdapter;
import dev.aegis.provider.spi.ProviderRegistry;
import dev.aegis.provider.spi.RawMessage;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Checks a browser callback inline: the customer is waiting, and a browser never retries, so there
 * is no ingress log in between. The same {@link CheckMessageUseCase} decides, so a callback gets
 * every check a webhook gets. The return page is display only: the application acts on the
 * forwarded message, never on the browser's redirect.
 */
@Service
public class CallbackService implements HandleCallbackUseCase {

    static final String UNKNOWN = "unknown";

    private final DirectoryPort directory;
    private final ProviderRegistry providers;
    private final CheckMessageUseCase checker;
    private final EvidenceCipherPort cipher;
    private final Clock clock;

    public CallbackService(
            DirectoryPort directory,
            ProviderRegistry providers,
            CheckMessageUseCase checker,
            EvidenceCipherPort cipher,
            Clock clock) {
        this.directory = directory;
        this.providers = providers;
        this.checker = checker;
        this.cipher = cipher;
        this.clock = clock;
    }

    @Override
    public URI handle(String connectionName, RawMessage message) {
        Connection connection =
                directory.connection(connectionName).orElseThrow(() -> new UnknownConnectionException(connectionName));
        ProviderAdapter adapter = providers.adapter(connection.adapter()).orElseThrow();
        RawMessage clear = Safely.decrypt(adapter, Channel.BROWSER, message, connection);
        boolean verified = clear != null && Safely.verify(adapter, Channel.BROWSER, clear, connection);
        CheckMessageUseCase.Result result = checker.check(new IngressRecord(
                UUID.randomUUID(),
                connection.name(),
                Channel.BROWSER,
                clock.instant(),
                verified,
                connection.name(),
                message.headers(),
                cipher.seal(message.body())));

        Application application =
                directory.application(connection.application()).orElseThrow();
        String ref = result.merchantRef() == null ? UNKNOWN : result.merchantRef();
        String verdict = result.verdict().passed() ? "PASSED" : "BLOCKED";
        return returnUrl(application, ref, verdict);
    }

    /** {@code return-url?ref=…&verdict=…&signature=hex(HMAC(signing secret, ref + "." + verdict))}. */
    static URI returnUrl(Application application, String ref, String verdict) {
        if (application.returnUrl() == null) {
            throw new IllegalStateException("application " + application.name() + " has no return-url");
        }
        String base = application.returnUrl().toString();
        String signature = Hmac.sha256Hex(application.signingSecret(), ref + "." + verdict);
        return URI.create(base + (base.contains("?") ? "&" : "?") + "ref=" + encode(ref) + "&verdict=" + verdict
                + "&signature=" + signature);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
