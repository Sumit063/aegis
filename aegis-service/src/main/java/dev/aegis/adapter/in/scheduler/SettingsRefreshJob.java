package dev.aegis.adapter.in.scheduler;

import dev.aegis.application.port.in.SettingsUseCase;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every instance reloads the runtime settings at start and every 5 seconds (DECISIONS D10). */
@Component
class SettingsRefreshJob {

    private final SettingsUseCase settings;

    SettingsRefreshJob(SettingsUseCase settings) {
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${aegis.settings.refresh:5s}")
    void refresh() {
        settings.refresh();
    }
}
