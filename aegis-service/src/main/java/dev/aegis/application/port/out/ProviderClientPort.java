package dev.aegis.application.port.out;

import dev.aegis.provider.spi.PreparedCall;
import java.util.List;
import java.util.Map;

/** Sends a prepared call to a provider. Never called inside a database transaction. */
public interface ProviderClientPort {

    /** @throws IllegalStateException when the provider cannot be reached or does not answer in time */
    Response send(PreparedCall call);

    record Response(int status, Map<String, List<String>> headers, byte[] body) {

        public Response {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public String contentType() {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase("content-type")
                            && !e.getValue().isEmpty())
                    .map(e -> e.getValue().getFirst())
                    .findFirst()
                    .orElse("application/octet-stream");
        }
    }
}
