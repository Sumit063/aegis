package dev.aegis.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Creates the annotated bean only in processes that run {@code value} ({@code aegis.roles}), so one
 * image can run as ingress-only, checker-only, or everything (HLD §7).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(RoleCondition.class)
public @interface ConditionalOnRole {

    Role value();
}
