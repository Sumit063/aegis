package dev.aegis.provider.spi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Provider XML, read safely (DECISIONS D33): no DOCTYPE, so no external entities and no entity
 * expansion; no XInclude; comments dropped (signatures exclude them too). Lookups are strict: a field
 * the contract expects once must be there exactly once, so an ambiguous message is never read.
 */
public final class Xml {

    private static final ErrorHandler STRICT = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {}

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    };

    private Xml() {}

    /** Parses provider XML; anything malformed, or carrying a DOCTYPE, is unparseable. */
    public static Document parse(byte[] body) {
        try {
            Document document = builder().parse(new ByteArrayInputStream(body));
            if (document.getDocumentElement() == null) {
                throw new UnparseableMessageException("no root element");
            }
            return document;
        } catch (SAXException | IOException e) {
            // The parser's message can quote the document; it is not repeated.
            throw new UnparseableMessageException("not well-formed XML, or XML with a DOCTYPE");
        }
    }

    /** The document as UTF-8 bytes, exactly as it will be signed or sent. */
    public static byte[] write(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newDefaultInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toByteArray();
        } catch (TransformerException e) {
            throw new IllegalStateException("XML could not be written", e);
        }
    }

    /** The root element, required to be {@code localName} in {@code namespace}. */
    public static Element root(Document document, String namespace, String localName) {
        Element root = document.getDocumentElement();
        if (!namespace.equals(root.getNamespaceURI()) || !localName.equals(root.getLocalName())) {
            throw new UnparseableMessageException("expected " + localName + ", got " + root.getLocalName());
        }
        return root;
    }

    /** The one unqualified child element named {@code name}; missing or repeated is unparseable. */
    public static Element one(Element parent, String name) {
        List<Element> found = children(parent, name);
        if (found.size() != 1) {
            throw new UnparseableMessageException(
                    parent.getLocalName() + " must have exactly one " + name + ", has " + found.size());
        }
        return found.getFirst();
    }

    /** The unqualified child element named {@code name}, if present once; repeated is unparseable. */
    public static Optional<Element> optional(Element parent, String name) {
        List<Element> found = children(parent, name);
        if (found.size() > 1) {
            throw new UnparseableMessageException(parent.getLocalName() + " has " + found.size() + " " + name);
        }
        return found.stream().findFirst();
    }

    /** Unqualified child elements named {@code name}. */
    public static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getNamespaceURI() == null && name.equals(e.getLocalName())) {
                found.add(e);
            }
        }
        return found;
    }

    /** A required, non-blank attribute. */
    public static String attr(Element element, String name) {
        return optionalAttr(element, name)
                .orElseThrow(() -> new UnparseableMessageException(element.getLocalName() + " without " + name));
    }

    /** An attribute, empty when absent or blank. */
    public static Optional<String> optionalAttr(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    private static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultNSInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setIgnoringComments(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(STRICT);
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("secure XML parsing unavailable", e);
        }
    }
}
