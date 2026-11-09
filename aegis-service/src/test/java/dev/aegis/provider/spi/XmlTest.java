package dev.aegis.provider.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Provider XML is read safely and strictly (DECISIONS D33). */
class XmlTest {

    private static final String NS = "http://bbps.org/schema";

    private static Document parse(String xml) {
        return Xml.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsTheRootChildrenAndAttributes() {
        Document document = parse("""
                <bbps:Ack xmlns:bbps="http://bbps.org/schema"><Head msgId="M1" ts=" 2026-11-10T10:00:00+05:30 "/></bbps:Ack>""");

        Element root = Xml.root(document, NS, "Ack");
        Element head = Xml.one(root, "Head");

        assertThat(Xml.attr(head, "msgId")).isEqualTo("M1");
        assertThat(Xml.attr(head, "ts")).isEqualTo("2026-11-10T10:00:00+05:30");
        assertThat(Xml.optionalAttr(head, "refId")).isEmpty();
        assertThat(Xml.optional(root, "Txn")).isEmpty();
    }

    /** XXE: an external entity is never resolved; the DOCTYPE alone refuses the document. */
    @Test
    void externalEntitiesAreRefused() {
        assertThatThrownBy(() -> parse("""
                        <?xml version="1.0"?>
                        <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                        <bbps:Ack xmlns:bbps="http://bbps.org/schema" a="&x;"/>"""))
                .isInstanceOf(UnparseableMessageException.class)
                .hasMessageNotContaining("passwd");
    }

    /** Billion laughs: refused at the DOCTYPE, before any expansion. */
    @Test
    @Timeout(2)
    void entityExpansionIsRefusedBeforeItStarts() {
        StringBuilder entities = new StringBuilder("<!ENTITY a0 \"lol\">");
        for (int i = 1; i <= 10; i++) {
            entities.append("<!ENTITY a%d \"&a%d;&a%d;&a%d;&a%d;&a%d;&a%d;&a%d;&a%d;&a%d;&a%d;\">"
                    .formatted(i, i - 1, i - 1, i - 1, i - 1, i - 1, i - 1, i - 1, i - 1, i - 1, i - 1));
        }
        String bomb = "<!DOCTYPE r [" + entities + "]><r>&a10;</r>";

        assertThatThrownBy(() -> parse(bomb)).isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void xincludeIsNotProcessed() {
        Document document = parse("""
                <r xmlns:xi="http://www.w3.org/2001/XInclude"><xi:include href="file:///etc/passwd" parse="text"/></r>""");

        assertThat(document.getDocumentElement().getTextContent()).isEmpty();
    }

    @Test
    void malformedXmlIsUnparseableWithoutEchoingIt() {
        assertThatThrownBy(() -> parse("<r><secret-value-1234></r>"))
                .isInstanceOf(UnparseableMessageException.class)
                .hasMessageNotContaining("secret-value-1234");
        assertThatThrownBy(() -> Xml.parse(new byte[0])).isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void theRootMustBeTheExpectedElementInTheExpectedNamespace() {
        assertThatThrownBy(() -> Xml.root(parse("<bbps:Ack xmlns:bbps=\"http://other\"/>"), NS, "Ack"))
                .isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> Xml.root(parse("<bbps:Nack xmlns:bbps=\"" + NS + "\"/>"), NS, "Ack"))
                .isInstanceOf(UnparseableMessageException.class);
    }

    /** A field expected once is never read from an ambiguous message (the first of two, or a qualified one). */
    @Test
    void anExpectedElementMustBeThereExactlyOnceAndUnqualified() {
        Element repeated = parse("<r><Txn id=\"1\"/><Txn id=\"2\"/></r>").getDocumentElement();
        Element qualified = parse("<r xmlns:x=\"urn:x\"><x:Txn id=\"1\"/></r>").getDocumentElement();

        assertThatThrownBy(() -> Xml.one(repeated, "Txn")).isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> Xml.optional(repeated, "Txn")).isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> Xml.one(qualified, "Txn")).isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void aMissingOrBlankAttributeIsUnparseable() {
        Element element = parse("<r a=\" \"/>").getDocumentElement();

        assertThatThrownBy(() -> Xml.attr(element, "a")).isInstanceOf(UnparseableMessageException.class);
        assertThatThrownBy(() -> Xml.attr(element, "b")).isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void writesWhatItReads() {
        String xml = "<bbps:Ack xmlns:bbps=\"" + NS + "\" api=\"X\"><Head msgId=\"M1\"/></bbps:Ack>";

        Document again = Xml.parse(Xml.write(parse(xml)));

        assertThat(Xml.attr(Xml.one(Xml.root(again, NS, "Ack"), "Head"), "msgId"))
                .isEqualTo("M1");
    }
}
