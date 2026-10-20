package com.eudistack.audit.infrastructure.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * Registers a bean only when {@code audit.role} is one of the given roles.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(ConditionalOnAuditRole.OnAuditRole.class)
public @interface ConditionalOnAuditRole {

    AuditRole[] value();

    final class OnAuditRole implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnAuditRole.class.getName());
            if (attributes == null) {
                return false;
            }
            AuditRole current = currentRole(context);
            return Arrays.asList((AuditRole[]) attributes.get("value")).contains(current);
        }

        static AuditRole currentRole(ConditionContext context) {
            String raw = context.getEnvironment().getProperty("audit.role", "all");
            return AuditRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
    }
}
