package dev.aegis.provider.spi;

import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;

/**
 * What an application's request says, read before it goes out. Each field is null when the request
 * does not carry it (a validation has no provider reference yet; a status check has no amount).
 */
public record CallFacts(Ref merchantRef, Ref providerRef, Ref paymentRef, Money amount) {}
