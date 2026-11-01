package dev.aegis.application.port.in;

/** The Forwarder (LLD §8): delivers passed messages to applications, in order, until acknowledged. */
public interface ForwardUseCase {

    /** One pass over what is due; returns how many messages were delivered. */
    int forwardDue();
}
