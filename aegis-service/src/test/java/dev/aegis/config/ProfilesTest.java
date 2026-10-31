package dev.aegis.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** Guards the profile files: each names its environment, and only `local` may default credentials. */
class ProfilesTest {

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "staging", "prod"})
    void eachProfileDeclaresItsEnvironment(String profile) {
        assertThat(load("application-" + profile + ".yaml").getProperty("aegis.environment"))
                .isEqualTo(profile);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "staging", "prod"})
    void serverProfilesNeverDefaultCredentials(String profile) {
        Properties merged = load("application.yaml");
        merged.putAll(load("application-" + profile + ".yaml"));

        assertThat(merged.getProperty("spring.datasource.password")).isEqualTo("${AEGIS_DB_PASSWORD}");
        assertThat(merged.getProperty("spring.datasource.url")).isEqualTo("${AEGIS_DB_URL}");
        assertThat(merged.stringPropertyNames())
                .allSatisfy(key -> assertThat(merged.getProperty(key)).doesNotContain("local-only"));
    }

    private static Properties load(String resource) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(resource));
        Properties properties = yaml.getObject();
        assertThat(properties).as(resource).isNotNull();
        return properties;
    }
}
