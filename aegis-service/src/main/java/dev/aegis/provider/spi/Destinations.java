package dev.aegis.provider.spi;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Where Aegis may send requests (HARDENING §8, SSRF). Destinations come only from operator
 * configuration, never from a request; this guards against mistakes and DNS tricks:
 *
 * <ul>
 *   <li>only {@code http}/{@code https}, with a host;
 *   <li>never link-local or cloud-metadata addresses (169.254.0.0/16, fe80::/10,
 *       {@code metadata.google.internal}), checked on the name and on every resolved address;
 *   <li>{@code https} required unless plain HTTP is explicitly allowed (private self-hosted networks).
 * </ul>
 *
 * Private and loopback addresses are allowed: self-hosted merchants live there.
 */
public final class Destinations {

    private static final Set<String> METADATA_HOSTS = Set.of("metadata.google.internal", "metadata", "instance-data");

    private Destinations() {}

    /** @throws IllegalArgumentException with a reason when {@code uri} is not an allowed destination */
    public static void check(URI uri, boolean allowPlainHttp) {
        if (uri == null || uri.getHost() == null) {
            throw new IllegalArgumentException("destination needs a scheme and a host");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && allowPlainHttp)) {
            throw new IllegalArgumentException("destination must be https" + (allowPlainHttp ? " or http" : ""));
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (METADATA_HOSTS.contains(host)) {
            throw new IllegalArgumentException("destination is a cloud metadata service");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("destination resolves to a link-local or metadata address");
                }
            }
        } catch (UnknownHostException e) {
            // Unresolvable now (e.g. a service not started yet); checked again before each send.
        }
    }
}
