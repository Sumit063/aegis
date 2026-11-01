package dev.aegis.adapter.in.scheduler;

import dev.aegis.application.port.in.ForwardUseCase;
import dev.aegis.config.ConditionalOnRole;
import dev.aegis.config.Role;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the Forwarder. Every half second it drains what is due: while a pass finds work, the next
 * starts at once (lab/bench showed a fixed pace capping delivery at ~100 payments/s), bounded so the
 * scheduler thread is never held for long.
 */
@Component
@ConditionalOnRole(Role.FORWARDER)
class ForwarderJob {

    private static final Duration DRAIN_LIMIT = Duration.ofSeconds(5);

    private final ForwardUseCase forwarder;

    ForwarderJob(ForwardUseCase forwarder) {
        this.forwarder = forwarder;
    }

    @Scheduled(fixedDelayString = "${aegis.forwarder.interval:500ms}")
    void run() {
        long until = System.nanoTime() + DRAIN_LIMIT.toNanos();
        boolean more = true;
        while (more && System.nanoTime() < until) {
            more = forwarder.forwardDue() > 0;
        }
    }
}
