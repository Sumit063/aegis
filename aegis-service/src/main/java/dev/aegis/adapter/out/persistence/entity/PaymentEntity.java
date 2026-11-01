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

/**
 * Row of {@code payments}. {@code messages} is the JSON array of every message about the payment;
 * {@code forwardDueAt} is set while a passed message still has to reach the application.
 */
@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "validation_id")
    private Long validationId;

    @Column(nullable = false)
    private String provider;

    @Column(name = "payment_ref")
    private String paymentRef;

    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String messages;

    @Column(name = "forward_due_at")
    private Instant forwardDueAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentEntity() {}

    public PaymentEntity(Long validationId, String provider, String paymentRef, String messages, Instant now) {
        this.validationId = validationId;
        this.provider = provider;
        this.paymentRef = paymentRef;
        this.messages = messages;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getValidationId() {
        return validationId;
    }

    public String getProvider() {
        return provider;
    }

    public String getPaymentRef() {
        return paymentRef;
    }

    public String getStatus() {
        return status;
    }

    public String getMessages() {
        return messages;
    }

    public Instant getForwardDueAt() {
        return forwardDueAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void update(String status, String messages, Instant forwardDueAt, Instant now) {
        this.status = status;
        this.messages = messages;
        this.forwardDueAt = forwardDueAt;
        this.updatedAt = now;
    }
}
