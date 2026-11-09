package dev.aegis.lab.npci;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * The switch's XML signatures, written independently of Aegis on the JDK API: enveloped, over the
 * whole document, exclusive C14N, RSA-SHA256 (NPCI.md §1). Off-profile signing exists only to
 * produce attack messages.
 */
final class XmlDsig {

    private XmlDsig() {}

    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static PrivateKey privateKey(String base64) {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(
                            new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(base64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("npci.switch-key is not a PKCS#8 RSA key");
        }
    }

    static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(
                            new X509EncodedKeySpec(Base64.getMimeDecoder().decode(base64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("npci.aegis-key is not an RSA public key");
        }
    }

    /** Signs {@code xml} as the switch does. */
    static String sign(String xml, PrivateKey key) {
        return sign(xml, key, SignatureMethod.RSA_SHA256, null);
    }

    /** Signs with any method and key information: how attack messages are made. */
    static String sign(String xml, Key key, String method, KeyInfo keyInfo) {
        try {
            Document document = parse(xml);
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            var reference = factory.newReference(
                    "",
                    factory.newDigestMethod(DigestMethod.SHA256, null),
                    List.of(
                            factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                            factory.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)),
                    null,
                    null);
            var signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                    factory.newSignatureMethod(method, null),
                    List.of(reference));
            DOMSignContext context = new DOMSignContext(key, document.getDocumentElement());
            context.setDefaultNamespacePrefix("ds");
            factory.newXMLSignature(signedInfo, keyInfo).sign(context);
            return write(document);
        } catch (GeneralSecurityException
                | javax.xml.crypto.MarshalException
                | javax.xml.crypto.dsig.XMLSignatureException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    /** Whether {@code xml} carries exactly one signature, made by {@code key}. */
    static boolean verify(String xml, PublicKey key) {
        try {
            Document document = parse(xml);
            NodeList signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
            if (signatures.getLength() != 1) {
                return false;
            }
            DOMValidateContext context = new DOMValidateContext(key, signatures.item(0));
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            return XMLSignatureFactory.getInstance("DOM")
                    .unmarshalXMLSignature(context)
                    .validate(context);
        } catch (Exception e) {
            return false;
        }
    }

    static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultNSInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("not well-formed XML");
        }
    }

    static String write(Document document) {
        try {
            StringWriter out = new StringWriter();
            TransformerFactory.newDefaultInstance()
                    .newTransformer()
                    .transform(new DOMSource(document), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
