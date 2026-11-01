package dev.aegis.application.port.out;

/**
 * Encrypts message bodies before they are stored, and decrypts them for forwarding or investigation
 * (LLD §7: evidence is private). Sealed text names its key, so keys can be rotated.
 */
public interface EvidenceCipherPort {

    /** Encrypts with the current key; returns {@code enc:v1:<key id>:<base64>}. */
    String seal(byte[] plain);

    /** Decrypts text sealed with any configured key. */
    byte[] open(String sealed);
}
