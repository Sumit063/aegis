package dev.aegis.application.port.out;

/** Storage of runtime settings in the {@code config} table, versioned and attributed. */
public interface SettingsRepository {

    Settings load();

    void save(Settings settings, String updatedBy);
}
