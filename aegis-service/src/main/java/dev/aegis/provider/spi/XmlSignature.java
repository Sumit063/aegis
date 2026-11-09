package dev.aegis.provider.spi;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The one XML signature profile Aegis signs and accepts (DECISIONS D33): an enveloped signature, the
 * root's last child, over the whole document ({@code URI=""}), exclusive C14N, SHA-256, RSA-SHA256
 * with a key of at least 2048 bits. A message is checked against this shape before any cryptography,
 * and verified only with keys from configuration; the key information a message carries is ignored.
 */
public final class XmlSignature {

    public static final int MIN_KEY_BITS = 2048;
    private static final String SECURE_VALIDATION = "org.jcp.xml.dsig.secureValidation";
    private static final List<String> TRANSFORMS = List.of(Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE);
    private static final Map<String, PublicKey> PUBLIC_KEYS = new ConcurrentHashMap<>();
    private static final Map<String, PrivateKey> PRIVATE_KEYS = new ConcurrentHashMap<>();

    private XmlSignature() {}

    /** Signs {@code document} in place; a document that already carries a signature is refused. */
    public static void sign(Document document, PrivateKey key) {
        if (signatures(document).getLength() != 0) {
            throw new UnparseableMessageException("the document is already signed");
        }
        try {
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            Reference reference = factory.newReference(
                    "",
                    factory.newDigestMethod(DigestMethod.SHA256, null),
                    List.of(
                            factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                            factory.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)),
                    null,
                    null);
            SignedInfo signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                    factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                    List.of(reference));
            DOMSignContext context = new DOMSignContext(key, document.getDocumentElement());
            context.setDefaultNamespacePrefix("ds");
            factory.newXMLSignature(signedInfo, null).sign(context);
        } catch (GeneralSecurityException | MarshalException | XMLSignatureException e) {
            throw new IllegalStateException("XML signing failed", e);
        }
    }

    /** Whether {@code document} carries one profile signature made by one of {@code keys}. */
    public static boolean verify(Document document, List<PublicKey> keys) {
        NodeList found = signatures(document);
        if (found.getLength() != 1) {
            return false;
        }
        Element signature = (Element) found.item(0);
        if (signature.getParentNode() != document.getDocumentElement() || nextElement(signature) != null) {
            return false;
        }
        for (PublicKey key : keys) {
            if (validates(signature, key)) {
                return true;
            }
        }
        return false;
    }

    /** Aegis's signing key: PKCS#8, PEM or single-line base64. */
    public static PrivateKey privateKey(Secret secret) {
        return PRIVATE_KEYS.computeIfAbsent(secret.value(), value -> {
            PrivateKey key;
            try {
                key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der(value)));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalArgumentException("not an RSA PKCS#8 private key");
            }
            return strong(key);
        });
    }

    /** A trusted key: an X.509 certificate or a public key (SubjectPublicKeyInfo), PEM or base64. */
    public static PublicKey publicKey(Secret secret) {
        return PUBLIC_KEYS.computeIfAbsent(secret.value(), value -> {
            byte[] der;
            try {
                der = der(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("not a certificate or public key");
            }
            try {
                return strong(CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(der))
                        .getPublicKey());
            } catch (CertificateException notACertificate) {
                try {
                    return strong(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der)));
                } catch (GeneralSecurityException e) {
                    throw new IllegalArgumentException("not a certificate or RSA public key");
                }
            }
        });
    }

    private static boolean validates(Element signature, PublicKey key) {
        try {
            DOMValidateContext context = new DOMValidateContext(key, signature);
            context.setProperty(SECURE_VALIDATION, Boolean.TRUE);
            XMLSignature parsed = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            return profile(parsed.getSignedInfo()) && parsed.getObjects().isEmpty() && parsed.validate(context);
        } catch (MarshalException | XMLSignatureException | RuntimeException e) {
            return false;
        }
    }

    // Checked before validation: no other algorithm, reference or transform is ever evaluated.
    private static boolean profile(SignedInfo info) {
        if (!CanonicalizationMethod.EXCLUSIVE.equals(
                        info.getCanonicalizationMethod().getAlgorithm())
                || !SignatureMethod.RSA_SHA256.equals(info.getSignatureMethod().getAlgorithm())
                || info.getReferences().size() != 1) {
            return false;
        }
        Reference reference = (Reference) info.getReferences().getFirst();
        List<String> transforms = reference.getTransforms().stream()
                .map(t -> ((Transform) t).getAlgorithm())
                .toList();
        return "".equals(reference.getURI())
                && reference.getType() == null
                && DigestMethod.SHA256.equals(reference.getDigestMethod().getAlgorithm())
                && TRANSFORMS.equals(transforms);
    }

    private static NodeList signatures(Document document) {
        return document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
    }

    private static Element nextElement(Node node) {
        for (Node n = node.getNextSibling(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        return null;
    }

    private static <K extends Key> K strong(K key) {
        if (!(key instanceof RSAKey rsa) || rsa.getModulus().bitLength() < MIN_KEY_BITS) {
            throw new IllegalArgumentException("RSA keys must have at least " + MIN_KEY_BITS + " bits");
        }
        return key;
    }

    // PEM (with any header, real or escaped newlines) or bare base64.
    private static byte[] der(String value) {
        String base64 = value.replace("\\n", "\n")
                .lines()
                .filter(line -> !line.startsWith("-----"))
                .map(String::strip)
                .reduce("", String::concat);
        return Base64.getDecoder().decode(base64);
    }
}
