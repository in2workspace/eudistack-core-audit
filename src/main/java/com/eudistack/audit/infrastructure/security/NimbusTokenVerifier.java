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
 * {@code aud} containing {@code audit.security.audience} when it is configured (PO-12).
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

    private final DefaultJWTProcessor<SecurityContext> processor;
    private final List<Pattern> trustedIssuers;
    private final CallerClaims callerClaims;

    public NimbusTokenVerifier(JWKSource<SecurityContext> jwks, AuditProperties.Security security,
                               CallerClaims callerClaims) {
        this.callerClaims = callerClaims;
        this.trustedIssuers = security.getTrustedIssuers().stream().map(NimbusTokenVerifier::issuerPattern).toList();
        Set<String> audience = security.getAudience() == null || security.getAudience().isBlank()
                ? null : Set.of(security.getAudience());
        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier =
                new DefaultJWTClaimsVerifier<>(audience, null, Set.of("exp", "iss", "sub"), null);
        claimsVerifier.setMaxClockSkew((int) security.getClockSkew().toSeconds());
        this.processor = new DefaultJWTProcessor<>();
        this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, jwks));
        this.processor.setJWTClaimsSetVerifier(claimsVerifier);
    }

    @Override
    public Caller verify(String bearerToken) {
        JWTClaimsSet claims;
        try {
            claims = processor.process(SignedJWT.parse(bearerToken), null);
        } catch (KeySourceException e) {
            throw new ServiceUnavailableException("The Verifier JWKS is not available", e);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new InvalidTokenException("The access token is not valid");
        }
        String issuer = claims.getIssuer();
        if (trustedIssuers.stream().noneMatch(p -> p.matcher(issuer).matches())) {
            throw new InvalidTokenException("The access token was not issued by a trusted Verifier");
        }
        return callerClaims.toCaller(claims);
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
