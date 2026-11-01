package dev.aegis.adapter.in.web.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Private APIs require an authenticated application; {@code /in/**} is public and verified per message. */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration implements WebMvcConfigurer {

    private final ApplicationAuthentication applications;

    SecurityConfiguration(ApplicationAuthentication applications) {
        this.applications = applications;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(applications).addPathPatterns("/gw/**");
    }
}
