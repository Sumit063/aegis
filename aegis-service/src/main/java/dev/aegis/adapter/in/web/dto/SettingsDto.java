package dev.aegis.adapter.in.web.dto;

import dev.aegis.application.port.out.Settings;
import java.util.Set;

/** Runtime settings over HTTP. */
public record SettingsDto(Set<String> pausedApplications) {

    public static SettingsDto of(Settings settings) {
        return new SettingsDto(settings.pausedApplications());
    }

    public Settings toSettings() {
        return new Settings(pausedApplications);
    }
}
