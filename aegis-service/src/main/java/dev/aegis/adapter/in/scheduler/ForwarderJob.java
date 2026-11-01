package dev.aegis.adapter.in.scheduler;

import dev.aegis.application.port.in.ForwardUseCase;
import dev.aegis.config.ConditionalOnRole;
import dev.aegis.config.Role;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the Forwarder: a pass every half second; each pass takes what is due. */
@Component
@ConditionalOnRole(Role.FORWARDER)
class ForwarderJob {

    private final ForwardUseCase forwarder;

    ForwarderJob(ForwardUseCase forwarder) {
        this.forwarder = forwarder;
    }

    @Scheduled(fixedDelayString = "${aegis.forwarder.interval:500ms}")
    void run() {
        forwarder.forwardDue();
    }
}
