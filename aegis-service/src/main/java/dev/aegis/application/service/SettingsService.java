package dev.aegis.application.service;

import dev.aegis.application.port.in.SettingsUseCase;
import dev.aegis.application.port.out.Settings;
import dev.aegis.application.port.out.SettingsRepository;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Holds an immutable snapshot of the runtime settings; readers never touch the database. A failed
 * refresh keeps the last good snapshot.
 */
@Service
public class SettingsService implements SettingsUseCase {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final SettingsRepository repository;
    private final AtomicReference<Settings> snapshot = new AtomicReference<>(Settings.DEFAULTS);

    public SettingsService(SettingsRepository repository) {
        this.repository = repository;
    }

    @Override
    public Settings current() {
        return snapshot.get();
    }

    @Override
    public Settings update(Settings settings, String updatedBy) {
        repository.save(settings, updatedBy);
        snapshot.set(settings);
        log.info("settings changed by {}: {}", updatedBy, settings);
        return settings;
    }

    @Override
    public void refresh() {
        try {
            snapshot.set(repository.load());
        } catch (RuntimeException e) {
            log.warn("settings refresh failed; keeping the last snapshot: {}", e.getMessage());
        }
    }
}
