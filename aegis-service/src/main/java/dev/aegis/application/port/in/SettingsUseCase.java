package dev.aegis.application.port.in;

import dev.aegis.application.port.out.Settings;

/**
 * Runtime settings (DECISIONS D10): changed through Admin without a deployment, read from an
 * in-memory snapshot that every instance refreshes every few seconds.
 */
public interface SettingsUseCase {

    Settings current();

    Settings update(Settings settings, String updatedBy);

    /** Reloads the snapshot from the database. */
    void refresh();
}
