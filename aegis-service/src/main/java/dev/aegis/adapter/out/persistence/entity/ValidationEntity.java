package dev.aegis.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Row of {@code validations}. {@code data} is the JSON of the whole call (request and reply). */
@Entity
@Table(name = "validations")
public class ValidationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String provider;

    @Column(name = "merchant_ref", nullable = false)
    private String merchantRef;

    @Column(name = "provider_ref")
    private String providerRef;

    private Long amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String currency;

    @Column(nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String data;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ValidationEntity() {}

    public ValidationEntity(
            String provider,
            String merchantRef,
            String providerRef,
            Long amount,
            String currency,
            String status,
            String data,
            Instant now) {
        this.provider = provider;
        this.merchantRef = merchantRef;
        this.providerRef = providerRef;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.data = data;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getMerchantRef() {
        return merchantRef;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public Long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public String getData() {
        return data;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void changeStatus(String status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }
}
