package com.eudistack.audit.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Access tokens with the layout the Verifier issues ({@code power}, {@code mandator}, {@code mandatee},
 * {@code tenant}, {@code credential_type}), signed with a test key whose public JWKS the service trusts.
 */
public final class TestTokens {

    public static final String ISSUER_TEMPLATE = "https://{tenant}.stg.eudistack.net/verifier";
    private static final ECKey KEY = generate("test-key");
    private static final ECKey FOREIGN_KEY = generate("test-key");

    private TestTokens() {
    }

    public static String publicJwks() {
        return new JWKSet(KEY.toPublicJWK()).toString();
    }

    public static Builder token() {
        return new Builder();
    }

    /** A platform system with {@code organization · EUDISTACK · Audit · Write}. */
    public static String writer() {
        return token().system("issuer-client").tenant("platform").organization("VATES-EUDISTACK")
                .power("organization", "EUDISTACK", "Audit", "Write").sign();
    }

    /** A person with {@code organization · <TENANT> · Audit · Read}. */
    public static String tenantAuditor(String tenant) {
        return token().person("auditor-" + tenant).tenant(tenant).organization("VATES-B00000000")
                .power("organization", tenant.toUpperCase(), "Audit", "Read").sign();
    }

    /** A person with {@code domain · <TENANT> · Audit · Read} of the given organization. */
    public static String domainAuditor(String tenant, String organization) {
        return token().person("auditor-" + organization).tenant(tenant).organization(organization)
                .power("domain", tenant.toUpperCase(), "Audit", "Read").sign();
    }

    /** The platform administrator, from the platform tenant. */
    public static String administrator() {
        return token().person("platform-admin").tenant("platform").organization("VATES-EUDISTACK")
                .power("organization", "EUDISTACK", "System", "Administration").sign();
    }

    public static final class Builder {

        private final Map<String, Object> claims = new LinkedHashMap<>();
        private final List<Map<String, Object>> powers = new ArrayList<>();
        private String subject = "subject-" + UUID.randomUUID();
        private String issuerTenant;
        private String audience;
        private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
        private boolean foreignKey;

        public Builder person(String id) {
            this.subject = id;
            claims.put("credential_type", "learcredential.employee.sd.1");
            claims.put("scope", "openid learcredential");
            claims.put("mandatee", Map.of("firstName", "Ana", "lastName", "Pérez", "email", id + "@example.org"));
            return this;
        }

        public Builder system(String id) {
            this.subject = id;
            claims.put("credential_type", "learcredential.machine.sd.1");
            claims.put("scope", "machine learcredential");
            return this;
        }

        public Builder tenant(String tenant) {
            claims.put("tenant", tenant);
            if (issuerTenant == null) {
                issuerTenant = tenant;
            }
            return this;
        }

        public Builder organization(String organizationIdentifier) {
            claims.put("mandator", Map.of("organizationIdentifier", organizationIdentifier));
            return this;
        }

        public Builder power(String type, String domain, String function, String action) {
            powers.add(Map.of("type", type, "domain", domain, "function", function, "action", List.of(action)));
            return this;
        }

        public Builder issuer(String tenantLabel) {
            this.issuerTenant = tenantLabel;
            return this;
        }

        public Builder audience(String value) {
            this.audience = value;
            return this;
        }

        public Builder expiresAt(Instant value) {
            this.expiresAt = value;
            return this;
        }

        public Builder signedWithForeignKey() {
            this.foreignKey = true;
            return this;
        }

        public String sign() {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(ISSUER_TEMPLATE.replace("{tenant}", issuerTenant == null ? "platform" : issuerTenant))
                    .subject(subject)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(new Date())
                    .expirationTime(Date.from(expiresAt));
            if (audience != null) {
                builder.audience(audience);
            }
            claims.forEach(builder::claim);
            builder.claim("power", powers);
            try {
                ECKey key = foreignKey ? FOREIGN_KEY : KEY;
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(),
                        builder.build());
                jwt.sign(new ECDSASigner(key));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static ECKey generate(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
