package dev.aegis.config;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches when {@code aegis.roles} contains the role named by {@link ConditionalOnRole}. */
class RoleCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnRole.class.getName());
        if (attributes == null) {
            return false;
        }
        Role role = (Role) attributes.get("value");
        return Binder.get(context.getEnvironment())
                .bind("aegis.roles", Bindable.setOf(Role.class))
                .orElse(Set.of())
                .contains(role);
    }
}
