package com.eudistack.audit.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.eudistack.audit.domain.exception.InvalidTokenException;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.port.out.TokenVerifier;
import com.eudistack.audit.infrastructure.config.AuditProperties;

import java.text.ParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Verifies a Verifier access token (design §3.4.1): asymmetric signature against the Verifier JWKS,
 * {@code iss} among the trusted issuers, {@code exp} / {@code nbf} with clock skew, {@code sub}, and
 * {@code aud} containing one of {@code audit.security.audience} when it is configured. A configured value may
 * hold the tenant marker, resolved against the tenant of the request host (design AD-11).
 *
 * <p>The JWKS source caches the key set and refetches on an unknown {@code kid}, so key rotation in the
 * Verifier needs no redeploy here. A JWKS that cannot be fetched is a 503, never a 401.
 */
public final class NimbusTokenVerifier implements TokenVerifier {

    private static final Set<JWSAlgorithm> ALGORITHMS = new HashSet<>();

    static {
        ALGORITHMS.addAll(JWSAlgorithm.Family.EC);
        ALGORITHMS.addAll(JWSAlgorithm.Family.RSA);
        ALGORITHMS.addAll(JWSAlgorithm.Family.ED);
    }

    private static final String TENANT_PLACEHOLDER = "{tenant}";

    private final DefaultJWTProcessor<SecurityContext> processor;
    private final List<Pattern> trustedIssuers;
    private final List<String> audiences;
    private final CallerClaims callerClaims;

    public NimbusTokenVerifier(JWKSource<SecurityContext> jwks, AuditProperties.Security security,
                               CallerClaims callerClaims) {
        this.callerClaims = callerClaims;
        this.trustedIssuers = security.getTrustedIssuers().stream().map(NimbusTokenVerifier::issuerPattern).toList();
        this.audiences = security.getAudience().stream().filter(value -> !value.isBlank()).map(String::trim).toList();
        // The audience depends on the request host, so it is checked in verify(), not here.
        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier =
                new DefaultJWTClaimsVerifier<>((Set<String>) null, null, Set.of("exp", "iss", "sub"), null);
        claimsVerifier.setMaxClockSkew((int) security.getClockSkew().toSeconds());
        this.processor = new DefaultJWTProcessor<>();
        this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, jwks));
        this.processor.setJWTClaimsSetVerifier(claimsVerifier);
    }

    @Override
    public Caller verify(String bearerToken, String hostTenant) {
        JWTClaimsSet claims;
        try {
            claims = processor.process(SignedJWT.parse(bearerToken), null);
        } catch (KeySourceException e) {
            throw new ServiceUnavailableException("The Verifier JWKS is not available", e);
        } catch (ParseException | BadJOSEException | JOSEException _) {
            throw new InvalidTokenException("The access token is not valid");
        }
        if (!acceptsAudience(claims.getAudience(), hostTenant)) {
            throw new InvalidTokenException("The access token was not issued for this service");
        }
        String issuer = claims.getIssuer();
        if (trustedIssuers.stream().noneMatch(p -> p.matcher(issuer).matches())) {
            throw new InvalidTokenException("The access token was not issued by a trusted Verifier");
        }
        return callerClaims.toCaller(claims);
    }

    /**
     * Without a configured audience nothing is checked. A value with the tenant marker needs a host tenant: with
     * none, it matches nothing.
     */
    private boolean acceptsAudience(List<String> tokenAudience, String hostTenant) {
        if (audiences.isEmpty()) {
            return true;
        }
        return audiences.stream()
                .filter(value -> hostTenant != null || !value.contains(TENANT_PLACEHOLDER))
                .map(value -> hostTenant == null ? value : value.replace(TENANT_PLACEHOLDER, hostTenant))
                .anyMatch(tokenAudience::contains);
    }

    /**
     * {@code https://{tenant}.stg.eudistack.net/verifier} → one host label in place of {@code {tenant}}.
     */
    static Pattern issuerPattern(String template) {
        String[] parts = template.split("\\{tenant}", -1);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append("[a-z0-9-]+");
            }
            regex.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
