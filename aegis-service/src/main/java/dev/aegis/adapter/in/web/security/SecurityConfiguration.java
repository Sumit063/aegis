package dev.aegis.adapter.in.web.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Private APIs: {@code /gw/**} for authenticated applications, {@code /admin/**} for operators.
 * {@code /in/**} is public; every message there is verified on its own.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration implements WebMvcConfigurer {

    private final ApplicationAuthentication applications;
    private final AdminAuthentication operators;

    SecurityConfiguration(ApplicationAuthentication applications, AdminAuthentication operators) {
        this.applications = applications;
        this.operators = operators;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(applications).addPathPatterns("/gw/**");
        registry.addInterceptor(operators).addPathPatterns("/admin/**");
    }
}
