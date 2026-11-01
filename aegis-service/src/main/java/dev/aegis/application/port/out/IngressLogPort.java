package dev.aegis.application.port.out;

/** The durable buffer between Ingress and the Checker (Kafka, DECISIONS D6). */
public interface IngressLogPort {

    /** Returns once the record is durably stored; throws if it could not be. */
    void append(IngressRecord record);
}
