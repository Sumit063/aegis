package dev.aegis.adapter.in.web.dto;

import dev.aegis.domain.payment.Validation;

/** A validation as operators see it. */
public record ValidationDto(
        long id, String provider, String merchantRef, String providerRef, Long amount, String currency, String status) {

    public static ValidationDto of(Validation v) {
        return new ValidationDto(
                v.id(),
                v.provider().value(),
                v.merchantRef().value(),
                v.providerRef() == null ? null : v.providerRef().value(),
                v.amount() == null ? null : v.amount().minorUnits(),
                v.amount() == null ? null : v.amount().currency().getCurrencyCode(),
                v.status().name());
    }
}
