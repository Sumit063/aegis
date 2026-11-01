package dev.aegis.application.service;

import dev.aegis.application.port.in.AdminUseCase;
import dev.aegis.application.port.in.BadRequestException;
import dev.aegis.application.port.out.ForwardRepository;
import dev.aegis.application.port.out.PaymentRepository;
import dev.aegis.application.port.out.ValidationRepository;
import dev.aegis.domain.payment.Payment;
import dev.aegis.domain.payment.Validation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-mostly operations for operators. Never shows message bodies: evidence stays sealed. */
@Service
public class AdminService implements AdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final PaymentRepository payments;
    private final ValidationRepository validations;
    private final ForwardRepository forwarding;
    private final Clock clock;

    public AdminService(
            PaymentRepository payments, ValidationRepository validations, ForwardRepository forwarding, Clock clock) {
        this.payments = payments;
        this.validations = validations;
        this.forwarding = forwarding;
        this.clock = clock;
    }

    @Override
    public Optional<Payment> payment(long id) {
        return payments.find(id);
    }

    @Override
    public List<Payment> paymentsByRef(String ref) {
        return payments.findByRef(ref);
    }

    @Override
    public List<Validation> validationsByRef(String ref) {
        return validations.findByRef(ref);
    }

    @Override
    public List<Payment> blockedSince(Instant since, int limit) {
        return payments.blockedSince(since, Math.clamp(limit, 1, 500));
    }

    @Override
    @Transactional
    public void reforward(long paymentId, int entry) {
        if (!forwarding.reforward(paymentId, entry, clock.instant())) {
            throw new BadRequestException("no passed, forwarded message " + paymentId + "-" + entry);
        }
        log.info("re-forward requested for {}-{}", paymentId, entry);
    }
}
