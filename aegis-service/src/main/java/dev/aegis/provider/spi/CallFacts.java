package dev.aegis.provider.spi;

import dev.aegis.domain.shared.Money;
import dev.aegis.domain.shared.Ref;

/**
 * What an application's request says, read before it goes out. Each field is null when the request
 * does not carry it (a status check has no amount). A validation carries a provider reference only
 * where the application chooses it (NPCI's bill {@code refId}, UPI's {@code Txn@id}); a gateway's
 * order ID is the provider's, known from its reply.
 */
public record CallFacts(Ref merchantRef, Ref providerRef, Ref paymentRef, Money amount) {}
