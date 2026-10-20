package com.eudistack.audit.domain.model;

import java.util.List;

/**
 * A power of the caller's attestation: {@code type · domain · function · action}.
 *
 * <p>Same shape as the {@code power} claim the Verifier copies into the access token
 * ({@code mandate.power[]}). {@code domain} compares ignoring case: the tenant travels in lower case
 * in the host ({@code red-logistica}) and in upper case in the power ({@code RED-LOGISTICA}).
 */
public record Power(String type, String domain, String function, List<String> actions) {

    public Power {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public boolean matches(String expectedType, String expectedDomain, String expectedFunction, String expectedAction) {
        return expectedType.equals(type)
                && expectedDomain.equalsIgnoreCase(domain)
                && grants(expectedFunction, expectedAction);
    }

    public boolean grants(String expectedFunction, String expectedAction) {
        return expectedFunction.equals(function) && actions.contains(expectedAction);
    }
}
