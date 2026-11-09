package dev.aegis.provider.npci;

import dev.aegis.domain.check.Check;
import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;
import dev.aegis.provider.spi.Ack;
import dev.aegis.provider.spi.Connection;
import dev.aegis.provider.spi.PreparedCall;
import dev.aegis.provider.spi.RawMessage;
import dev.aegis.provider.spi.Secret;
import dev.aegis.provider.spi.UnparseableMessageException;
import dev.aegis.provider.spi.UntrustedReplyException;
import dev.aegis.provider.spi.Xml;
import dev.aegis.provider.spi.XmlSignature;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.crypto.dsig.XMLSignature;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** What BBPS and UPI share (docs/design/NPCI.md §1): keys, signing, Head, amounts, Ack, limits. */
final class Npci {

    static final String SIGNING_KEY = "signing-key";
    static final String SWITCH_CERTIFICATE = "switch-certificate";

    /** The switch signs each delivery when it sends it; an hour bounds redelivery. Repeats are caught by ID. */
    static final Duration FRESHNESS = Duration.ofHours(1);

    /** NPCI: the first status check no sooner than 90 seconds, at most 3 within 2 hours (HLD §4). */
    static final Check.Limits LIMITS = new Check.Limits(Duration.ofSeconds(90), 3, Duration.ofHours(2));

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern MINOR = Pattern.compile("[0-9]{1,15}");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,13}(\\.[0-9]{1,4})?");
    private static final Map<String, Currency> NUMERIC = Currency.getAvailableCurrencies().stream()
            .filter(c -> c.getNumericCode() > 0)
            .collect(Collectors.toMap(c -> "%03d".formatted(c.getNumericCode()), Function.identity(), (a, b) -> a));

    private Npci() {}

    /** Startup check: a base URL, Aegis's signing key and every switch key load (RSA ≥ 2048 bits). */
    static void validate(Connection connection) {
        String prefix = "aegis.connections." + connection.name();
        if (connection.baseUrl() == null) {
            throw new IllegalStateException(prefix + ".base-url is required (the switch)");
        }
        try {
            XmlSignature.privateKey(connection.secrets().current(SIGNING_KEY));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(prefix + ".secrets." + SIGNING_KEY + ": " + e.getMessage());
        }
        for (Secret certificate : connection.secrets().all(SWITCH_CERTIFICATE)) {
            try {
                XmlSignature.publicKey(certificate);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(prefix + ".secrets." + SWITCH_CERTIFICATE + ": " + e.getMessage());
            }
        }
    }

    /** A message from the switch: parsed, rooted in {@code namespace} and signed by a configured switch key. */
    static Optional<Document> verified(RawMessage message, String namespace, Connection connection) {
        try {
            Document document = Xml.parse(message.body());
            Element root = document.getDocumentElement();
            return namespace.equals(root.getNamespaceURI()) && XmlSignature.verify(document, trusted(connection))
                    ? Optional.of(document)
                    : Optional.empty();
        } catch (UnparseableMessageException e) {
            return Optional.empty();
        }
    }

    /** The switch's reply to a call: unreadable is unparseable, unsigned or forged is untrusted. */
    static Document reply(RawMessage reply, String namespace, String root, Connection connection) {
        Document document = Xml.parse(reply.body());
        if (!XmlSignature.verify(document, trusted(connection))) {
            throw new UntrustedReplyException("the switch's reply is not signed by a configured switch key");
        }
        Xml.root(document, namespace, root);
        return document;
    }

    /**
     * An application's request: the expected root, unsigned (the application holds no NPCI key; Aegis
     * signs). Anything else is refused before it can reach the switch.
     */
    static Element request(RawMessage request, String namespace, String root) {
        Document document = Xml.parse(request.body());
        if (document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").getLength() != 0) {
            throw new UnparseableMessageException("requests are signed by Aegis, not by the application");
        }
        return Xml.root(document, namespace, root);
    }

    /** The request signed with Aegis's current key, ready for the switch. */
    static PreparedCall call(Connection connection, String path, RawMessage request) {
        Document document = Xml.parse(request.body());
        XmlSignature.sign(document, XmlSignature.privateKey(connection.secrets().current(SIGNING_KEY)));
        String base = connection.baseUrl().toString().replaceAll("/+$", "");
        return new PreparedCall(
                "POST",
                URI.create(base + path),
                Map.of("Content-Type", "application/xml", "Accept", "application/xml"),
                Xml.write(document));
    }

    /** {@code Head@ts}: ISO-8601 with an offset, the time the switch signed the message. */
    static Instant ts(Element head) {
        try {
            return OffsetDateTime.parse(Xml.attr(head, "ts"), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant();
        } catch (DateTimeParseException e) {
            throw new UnparseableMessageException("Head@ts is not an ISO-8601 time with an offset");
        }
    }

    /** {@code Head@msgId}: the message's identity across redeliveries. */
    static Ref messageId(Element head) {
        return Ref.of("msg:" + id(head, "msgId"));
    }

    /** A reference that may be placed in a URL or matched: an ID's characters only. */
    static Ref id(Element element, String attribute) {
        String value = Xml.attr(element, attribute);
        if (!ID.matcher(value).matches()) {
            throw new UnparseableMessageException(element.getLocalName() + "@" + attribute + " is not a valid ID");
        }
        return Ref.of(value);
    }

    /** BBPS: integer minor units with an ISO 4217 numeric currency ({@code 84000}, {@code 356}). */
    static Money minorUnits(Element amount) {
        String value = Xml.attr(amount, "amount");
        Currency currency = NUMERIC.get(Xml.attr(amount, "currency"));
        if (!MINOR.matcher(value).matches() || currency == null) {
            throw new UnparseableMessageException("amount must be integer minor units with a numeric currency");
        }
        return new Money(Long.parseLong(value), currency);
    }

    /** UPI: decimal major units ({@code 499.00}), converted exactly; finer than the currency is refused. */
    static Money majorUnits(String value, String currencyCode) {
        if (!DECIMAL.matcher(value).matches()) {
            throw new UnparseableMessageException("amount is not a plain decimal");
        }
        try {
            Currency currency = Currency.getInstance(currencyCode);
            BigDecimal minor = new BigDecimal(value).movePointRight(currency.getDefaultFractionDigits());
            if (currency.getDefaultFractionDigits() < 0
                    || minor.stripTrailingZeros().scale() > 0) {
                throw new UnparseableMessageException("amount is finer than " + currency + " allows");
            }
            return new Money(minor.longValueExact(), currency);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new UnparseableMessageException("bad amount or currency", e);
        }
    }

    /** The acknowledgement the switch waits for, naming the message it acknowledges. */
    static Ack ack(RawMessage message, String namespace, String prefix) {
        Document ack = Xml.parse(
                ("<" + prefix + ":Ack xmlns:" + prefix + "=\"" + namespace + "\"/>").getBytes(StandardCharsets.UTF_8));
        Element root = ack.getDocumentElement();
        try {
            Element received = Xml.parse(message.body()).getDocumentElement();
            root.setAttribute("api", received.getLocalName());
            Xml.optional(received, "Head")
                    .flatMap(head -> Xml.optionalAttr(head, "msgId"))
                    .ifPresent(id -> root.setAttribute("reqMsgId", id));
        } catch (UnparseableMessageException e) {
            // Acknowledged all the same: it is stored, which is what the Ack says.
        }
        root.setAttribute(
                "ts",
                OffsetDateTime.now(ZoneOffset.UTC)
                        .truncatedTo(ChronoUnit.SECONDS)
                        .toString());
        root.setAttribute("RspCd", "Successful");
        return new Ack(200, "application/xml", Xml.write(ack));
    }

    private static List<PublicKey> trusted(Connection connection) {
        return connection.secrets().all(SWITCH_CERTIFICATE).stream()
                .map(XmlSignature::publicKey)
                .toList();
    }
}
