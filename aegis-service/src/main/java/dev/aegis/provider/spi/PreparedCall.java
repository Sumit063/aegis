package dev.aegis.provider.spi;

import java.net.URI;
import java.util.Map;

/** A provider request ready to send: authenticated, signed and encrypted as the provider requires. */
public record PreparedCall(String method, URI uri, Map<String, String> headers, byte[] body) {

    public PreparedCall {
        headers = Map.copyOf(headers);
    }

    @Override
    public String toString() {
        // Headers carry credentials; never print them.
        return "PreparedCall[" + method + " " + uri + "]";
    }
}
