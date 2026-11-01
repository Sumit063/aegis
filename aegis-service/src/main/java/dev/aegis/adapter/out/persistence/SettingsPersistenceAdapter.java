package dev.aegis.adapter.out.persistence;

import dev.aegis.adapter.out.persistence.entity.ConfigEntity;
import dev.aegis.adapter.out.persistence.repository.ConfigJpaRepository;
import dev.aegis.application.port.out.Settings;
import dev.aegis.application.port.out.SettingsRepository;
import java.time.Clock;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link SettingsRepository} on the {@code config} table: one row per setting, JSON value. */
@Component
class SettingsPersistenceAdapter implements SettingsRepository {

    static final String PAUSED = "forwarding.paused-applications";
    private static final TypeReference<Set<String>> NAMES = new TypeReference<>() {};

    private final ConfigJpaRepository rows;
    private final JsonMapper json;
    private final Clock clock;

    SettingsPersistenceAdapter(ConfigJpaRepository rows, JsonMapper json, Clock clock) {
        this.rows = rows;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Settings load() {
        return new Settings(rows.findById(PAUSED)
                .map(row -> json.readValue(row.getValue(), NAMES))
                .orElse(Set.of()));
    }

    @Override
    @Transactional
    public void save(Settings settings, String updatedBy) {
        ConfigEntity row = rows.findById(PAUSED).orElseGet(() -> new ConfigEntity(PAUSED));
        row.change(json.writeValueAsString(settings.pausedApplications()), updatedBy, clock.instant());
        rows.save(row);
    }
}
