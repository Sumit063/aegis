package dev.aegis.adapter.in.scheduler;

import dev.aegis.application.port.in.RetentionUseCase;
import dev.aegis.config.ConditionalOnRole;
import dev.aegis.config.Role;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Purges records past retention once a day, at a quiet hour (UTC). */
@Component
@ConditionalOnRole(Role.ADMIN)
class RetentionJob {

    private final RetentionUseCase retention;

    RetentionJob(RetentionUseCase retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${aegis.retention.cron:0 30 3 * * *}", zone = "UTC")
    void purge() {
        retention.purge();
    }
}
