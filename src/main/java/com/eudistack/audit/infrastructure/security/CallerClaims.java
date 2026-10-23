package com.eudistack.audit.infrastructure.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.Power;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the claims of a Verifier access token to a {@link Caller}, with the same token layout the
 * Issuer reads ({@code PolicyContextFactory}): the Verifier copies {@code mandate.power},
 * {@code mandate.mandator} and {@code mandate.mandatee} of the presented LEAR credential to top-level
 * {@code power}, {@code mandator} and {@code mandatee} claims ({@code access_token_embed} of the
 * credential profile), and adds {@code tenant} and {@code credential_type}.
 *
 * <ul>
 *   <li>System (M2M) or person (H2M): {@code credential_type} {@code learcredential.machine.*} or
 *       scope {@code machine} is a system; anything else is a person.</li>
 *   <li>Organization: {@code mandator.organizationIdentifier}.</li>
 *   <li>Tenant: {@code tenant}, lower case, without environment suffix.</li>
 *   <li>Powers: top-level {@code power}; when absent, {@code vc.credentialSubject.mandate.power}.</li>
 * </ul>
 */
public final class CallerClaims {

    private static final String POWER = "power";

    private final List<String> environmentSuffixes;

    public CallerClaims(List<String> environmentSuffixes) {
        this.environmentSuffixes = List.copyOf(environmentSuffixes);
    }

    public Caller toCaller(JWTClaimsSet claims) {
        Map<String, Object> mandator = map(claims.getClaim("mandator"));
        Map<String, Object> mandatee = map(claims.getClaim("mandatee"));
        Map<String, Object> vc = map(claims.getClaim("vc"));
        Map<String, Object> mandate = map(map(vc.get("credentialSubject")).get("mandate"));
        if (mandator.isEmpty()) {
            mandator = map(mandate.get("mandator"));
        }
        if (mandatee.isEmpty()) {
            mandatee = map(mandate.get("mandatee"));
        }
        Object powerClaim = claims.getClaim(POWER) != null ? claims.getClaim(POWER) : mandate.get(POWER);

        ActorKind kind = isSystem(claims) ? ActorKind.SYSTEM : ActorKind.PERSON;
        String name = null;
        String email = null;
        if (kind == ActorKind.PERSON) {
            name = fullName(claims, mandatee);
            email = firstText(text(claims.getClaim("email")), text(mandatee.get("email")));
        }
        return new Caller(
                claims.getSubject(),
                kind,
                name,
                email,
                text(mandator.get("organizationIdentifier")),
                normaliseTenant(text(claims.getClaim("tenant"))),
                text(vc.get("id")),
                powers(powerClaim));
    }

    String normaliseTenant(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return null;
        }
        String value = tenant.trim().toLowerCase(Locale.ROOT);
        for (String suffix : environmentSuffixes) {
            if (value.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private static boolean isSystem(JWTClaimsSet claims) {
        String credentialType = text(claims.getClaim("credential_type"));
        if (credentialType != null && credentialType.toLowerCase(Locale.ROOT).contains(".machine")) {
            return true;
        }
        String scope = text(claims.getClaim("scope"));
        return scope != null && List.of(scope.split("\\s+")).contains("machine");
    }

    private static List<Power> powers(Object claim) {
        List<Power> powers = new ArrayList<>();
        if (claim instanceof Collection<?> list) {
            for (Object item : list) {
                Map<String, Object> power = map(item);
                if (!power.isEmpty()) {
                    powers.add(new Power(
                            alias(power, "type"),
                            alias(power, "domain"),
                            alias(power, "function"),
                            actions(power.get(power.containsKey("action") ? "action" : "tmf_action"))));
                }
            }
        }
        return powers;
    }

    private static List<String> actions(Object action) {
        if (action instanceof Collection<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return action == null ? List.of() : List.of(String.valueOf(action));
    }

    private static String alias(Map<String, Object> power, String field) {
        return firstText(text(power.get(field)), text(power.get("tmf_" + field)));
    }

    private static String fullName(JWTClaimsSet claims, Map<String, Object> mandatee) {
        String name = text(claims.getClaim("name"));
        if (name != null) {
            return name;
        }
        String first = firstText(text(mandatee.get("firstName")), text(mandatee.get("first_name")),
                text(claims.getClaim("given_name")));
        String last = firstText(text(mandatee.get("lastName")), text(mandatee.get("last_name")),
                text(claims.getClaim("family_name")));
        if (first == null && last == null) {
            return null;
        }
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
