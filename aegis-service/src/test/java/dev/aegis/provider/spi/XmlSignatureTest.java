package dev.aegis.provider.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.crypto.dsig.spec.XPathFilterParameterSpec;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** The one signature profile (DECISIONS D33), and every way around it that must fail. */
class XmlSignatureTest {

    private static final String RESPONSE = """
            <bbps:BillPaymentResponse xmlns:bbps="http://bbps.org/schema">\
            <Head ver="1.0" ts="2026-11-10T10:00:00+05:30" refId="R1" msgId="M1"/>\
            <Reason responseCode="000" responseReason="Successful"/>\
            <Txn txnReferenceId="T1"/>\
            <Amount><Amt amount="84000" currency="356"/></Amount>\
            </bbps:BillPaymentResponse>""";

    private static final List<PublicKey> TRUSTED = List.of(TestKeys.CURRENT.getPublic());

    private static Document document() {
        return Xml.parse(RESPONSE.getBytes(StandardCharsets.UTF_8));
    }

    private static Document signed(KeyPair pair) {
        Document document = document();
        XmlSignature.sign(document, pair.getPrivate());
        return document;
    }

    /** What a receiver gets: the bytes on the wire, parsed again. */
    private static Document wire(Document document) {
        return Xml.parse(Xml.write(document));
    }

    private static Document edit(Document document, String from, String to) {
        String xml = new String(Xml.write(document), StandardCharsets.UTF_8);
        assertThat(xml).contains(from);
        return Xml.parse(xml.replace(from, to).getBytes(StandardCharsets.UTF_8));
    }

    private static Element signatureOf(Document document) {
        return (Element)
                document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
    }

    @Test
    void aSignedDocumentVerifies() {
        assertThat(XmlSignature.verify(wire(signed(TestKeys.CURRENT)), TRUSTED)).isTrue();
    }

    /** T17 for keys: the previous key keeps verifying while it is configured. */
    @Test
    void anyConfiguredKeyVerifies() {
        List<PublicKey> rotated = List.of(TestKeys.CURRENT.getPublic(), TestKeys.PREVIOUS.getPublic());

        assertThat(XmlSignature.verify(wire(signed(TestKeys.PREVIOUS)), rotated))
                .isTrue();
        assertThat(XmlSignature.verify(wire(signed(TestKeys.PREVIOUS)), TRUSTED))
                .isFalse();
    }

    @Test
    void anotherKeyFails() {
        assertThat(XmlSignature.verify(wire(signed(TestKeys.ATTACKER)), TRUSTED))
                .isFalse();
        assertThat(XmlSignature.verify(wire(signed(TestKeys.CURRENT)), List.of()))
                .isFalse();
    }

    @Test
    void anyChangeAfterSigningFails() {
        Document genuine = signed(TestKeys.CURRENT);

        assertThat(XmlSignature.verify(edit(genuine, "amount=\"84000\"", "amount=\"84001\""), TRUSTED))
                .isFalse();
        assertThat(XmlSignature.verify(edit(genuine, "responseCode=\"000\"", "responseCode=\"001\""), TRUSTED))
                .isFalse();
        assertThat(XmlSignature.verify(
                        edit(
                                genuine,
                                "<Txn txnReferenceId=\"T1\"/>",
                                "<Txn txnReferenceId=\"T1\"/><Txn txnReferenceId=\"T2\"/>"),
                        TRUSTED))
                .isFalse();
    }

    @Test
    void anUnsignedDocumentFails() {
        assertThat(XmlSignature.verify(document(), TRUSTED)).isFalse();
    }

    @Test
    void twoSignaturesFailEvenIfOneIsGenuine() {
        Document document = signed(TestKeys.CURRENT);
        Element head = (Element) document.getDocumentElement().getFirstChild();
        head.appendChild(signatureOf(document).cloneNode(true));

        assertThat(XmlSignature.verify(wire(document), TRUSTED)).isFalse();
    }

    @Test
    void theSignatureMustBeTheRootsLastChild() {
        Document inside = signed(TestKeys.CURRENT);
        Element head = (Element) inside.getDocumentElement().getFirstChild();
        head.appendChild(signatureOf(inside));
        Document followed = signed(TestKeys.CURRENT);
        followed.getDocumentElement().appendChild(followed.createElement("Extra"));

        assertThat(XmlSignature.verify(wire(inside), TRUSTED)).isFalse();
        assertThat(XmlSignature.verify(wire(followed), TRUSTED)).isFalse();
    }

    /**
     * Signature wrapping: the genuine signed message is tucked inside a forged one and its signature
     * moved to the forged root, so a verifier that follows the reference would check the genuine part
     * while the reader reads the forgery. With one whole-document reference it fails.
     */
    @Test
    void wrappingAGenuineMessageInAForgedOneFails() {
        Document genuine = wire(signed(TestKeys.CURRENT));
        Document forged = edit(document(), "amount=\"84000\"", "amount=\"1\"");
        Element wrapper = forged.createElement("Wrapper");
        forged.getDocumentElement().appendChild(wrapper);
        Node original = forged.importNode(genuine.getDocumentElement(), true);
        wrapper.appendChild(original);
        forged.getDocumentElement().appendChild(signatureOf(forged));

        assertThat(XmlSignature.verify(wire(forged), TRUSTED)).isFalse();
    }

    /** Only {@code URI=""}: a reference to one element by ID is the shape wrapping attacks need. */
    @Test
    void aReferenceToPartOfTheDocumentFails() {
        Document document = document();
        Element txn = (Element) document.getElementsByTagName("Txn").item(0);
        txn.setAttribute("Id", "x");
        txn.setIdAttribute("Id", true);

        sign(document, TestKeys.CURRENT.getPrivate(), SignatureMethod.RSA_SHA256, "#x", standardTransforms(), null);

        assertThat(XmlSignature.verify(document, TRUSTED)).isFalse();
    }

    @Test
    void anyOtherTransformFails() throws Exception {
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        List<Transform> withXPath = List.of(
                factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                factory.newTransform(Transform.XPATH, new XPathFilterParameterSpec("not(self::Amount)")),
                factory.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null));
        Document document = document();

        sign(document, TestKeys.CURRENT.getPrivate(), SignatureMethod.RSA_SHA256, "", withXPath, null);

        assertThat(XmlSignature.verify(wire(document), TRUSTED)).isFalse();
    }

    @Test
    void anotherSignatureAlgorithmFailsEvenWithTheRightKey() {
        Document sha1 = document();
        sign(sha1, TestKeys.CURRENT.getPrivate(), SignatureMethod.RSA_SHA1, "", standardTransforms(), null);

        assertThat(XmlSignature.verify(wire(sha1), TRUSTED)).isFalse();
    }

    /** Key confusion: an HMAC keyed with the (public) verification key must not pass. */
    @Test
    void anHmacKeyedWithThePublicKeyFails() {
        Document hmac = document();
        Key confused = new SecretKeySpec(TestKeys.CURRENT.getPublic().getEncoded(), "HmacSHA256");
        sign(hmac, confused, SignatureMethod.HMAC_SHA256, "", standardTransforms(), null);

        assertThat(XmlSignature.verify(wire(hmac), TRUSTED)).isFalse();
    }

    /** The key a message names is ignored: only configured keys verify. */
    @Test
    void aKeyCarriedInTheMessageIsIgnored() throws Exception {
        KeyInfoFactory keys = XMLSignatureFactory.getInstance("DOM").getKeyInfoFactory();
        KeyInfo attackerKey = keys.newKeyInfo(List.of(keys.newKeyValue(TestKeys.ATTACKER.getPublic())));
        Document document = document();
        sign(
                document,
                TestKeys.ATTACKER.getPrivate(),
                SignatureMethod.RSA_SHA256,
                "",
                standardTransforms(),
                attackerKey);

        assertThat(XmlSignature.verify(wire(document), TRUSTED)).isFalse();
    }

    @Test
    void anAlreadySignedDocumentIsNotSignedAgain() {
        Document document = signed(TestKeys.CURRENT);

        assertThatThrownBy(() -> XmlSignature.sign(document, TestKeys.AEGIS.getPrivate()))
                .isInstanceOf(UnparseableMessageException.class);
    }

    @Test
    void keysLoadFromBase64PemAndCertificates() {
        assertThat(XmlSignature.publicKey(new Secret(TestKeys.publicKey(TestKeys.CURRENT))))
                .isEqualTo(TestKeys.CURRENT.getPublic());
        assertThat(XmlSignature.privateKey(new Secret(TestKeys.privateKey(TestKeys.CURRENT))))
                .isEqualTo(TestKeys.CURRENT.getPrivate());

        PublicKey fromCertificate = XmlSignature.publicKey(new Secret(TestKeys.certificate()));
        Document document = document();
        XmlSignature.sign(document, TestKeys.certificateKey());
        assertThat(XmlSignature.verify(wire(document), List.of(fromCertificate)))
                .isTrue();
    }

    @Test
    void weakOrUnreadableKeysAreRefusedWithoutEchoingThem() {
        KeyPair weak = TestKeys.generate(1024);

        assertThatThrownBy(() -> XmlSignature.publicKey(new Secret(TestKeys.publicKey(weak))))
                .hasMessageContaining("2048");
        assertThatThrownBy(() -> XmlSignature.privateKey(new Secret(TestKeys.privateKey(weak))))
                .hasMessageContaining("2048");
        assertThatThrownBy(() -> XmlSignature.publicKey(new Secret("not-a-key-but-a-secret-value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("not-a-key-but-a-secret-value");
        assertThatThrownBy(() -> XmlSignature.privateKey(new Secret(TestKeys.publicKey(TestKeys.CURRENT))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<Transform> standardTransforms() {
        try {
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            return List.of(
                    factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                    factory.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // Signs off-profile, as an attacker or a misbehaving switch would.
    private static void sign(
            Document document, Key key, String method, String uri, List<Transform> transforms, KeyInfo keyInfo) {
        try {
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            var reference = factory.newReference(
                    uri, factory.newDigestMethod(DigestMethod.SHA256, null), transforms, null, null);
            var signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                    factory.newSignatureMethod(method, null),
                    List.of(reference));
            factory.newXMLSignature(signedInfo, keyInfo).sign(new DOMSignContext(key, document.getDocumentElement()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
