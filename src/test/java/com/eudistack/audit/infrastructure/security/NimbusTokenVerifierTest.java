package com.eudistack.audit.infrastructure.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.eudistack.audit.domain.exception.InvalidTokenException;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.Power;
import com.eudistack.audit.infrastructure.config.AuditProperties;
import com.eudistack.audit.support.TestTokens;
import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NimbusTokenVerifierTest {

    private static final CallerClaims CLAIMS = new CallerClaims(List.of("-stg", "-dev", "-pre"));

    private static NimbusTokenVerifier verifier(String audience) throws ParseException {
        AuditProperties.Security security = new AuditProperties.Security();
        security.setTrustedIssuers(List.of(TestTokens.ISSUER_TEMPLATE));
        security.setAudience(audience);
        JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(JWKSet.parse(TestTokens.publicJwks()));
        return new NimbusTokenVerifier(jwks, security, CLAIMS);
    }

    @Test
    void verify_systemToken_isSystemWithPowersAndOrganization() throws Exception {
        Caller caller = verifier(null).verify(TestTokens.writer());

        assertThat(caller.kind()).isEqualTo(ActorKind.SYSTEM);
        assertThat(caller.subject()).isEqualTo("issuer-client");
        assertThat(caller.organization()).isEqualTo("VATES-EUDISTACK");
        assertThat(caller.tenant()).isEqualTo("platform");
        assertThat(caller.powers()).containsExactly(new Power("organization", "EUDISTACK", "Audit", List.of("Write")));
        assertThat(caller.name()).isNull();
    }

    @Test
    void verify_personToken_isPersonWithNameAndEmail() throws Exception {
        Caller caller = verifier(null).verify(TestTokens.tenantAuditor("red-logistica"));

        assertThat(caller.kind()).isEqualTo(ActorKind.PERSON);
        assertThat(caller.name()).isEqualTo("Ana Pérez");
        assertThat(caller.email()).isEqualTo("auditor-red-logistica@example.org");
        assertThat(caller.tenant()).isEqualTo("red-logistica");
    }

    @Test
    void verify_tenantWithEnvironmentSuffix_isNormalised() throws Exception {
        String token = TestTokens.token().person("p").tenant("Sandbox-STG").issuer("sandbox-stg").sign();

        assertThat(verifier(null).verify(token).tenant()).isEqualTo("sandbox");
    }

    @Test
    void verify_foreignSignature_isInvalid() {
        String token = TestTokens.token().person("p").tenant("sandbox").signedWithForeignKey().sign();

        assertThatThrownBy(() -> verifier(null).verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_expired_isInvalid() {
        String token = TestTokens.token().person("p").tenant("sandbox")
                .expiresAt(Instant.now().minus(Duration.ofMinutes(5))).sign();

        assertThatThrownBy(() -> verifier(null).verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_untrustedIssuer_isInvalid() {
        String token = TestTokens.token().person("p").tenant("sandbox").issuer("evil.example.com/x").sign();

        assertThatThrownBy(() -> verifier(null).verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_notAJwt_isInvalid() {
        assertThatThrownBy(() -> verifier(null).verify("not-a-token")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_audienceConfigured_requiresIt() throws Exception {
        NimbusTokenVerifier verifier = verifier("audit-api");

        assertThat(verifier.verify(TestTokens.token().person("p").tenant("sandbox").audience("audit-api").sign()))
                .isNotNull();
        assertThatThrownBy(() -> verifier.verify(TestTokens.token().person("p").tenant("sandbox").sign()))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> verifier.verify(TestTokens.token().person("p").tenant("sandbox")
                .audience("issuer-api").sign())).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_jwksUnavailable_isServiceUnavailable() {
        AuditProperties.Security security = new AuditProperties.Security();
        security.setTrustedIssuers(List.of(TestTokens.ISSUER_TEMPLATE));
        JWKSource<SecurityContext> down = (JWKSelector selector, SecurityContext context) -> {
            throw new KeySourceException("connection refused");
        };
        NimbusTokenVerifier verifier = new NimbusTokenVerifier(down, security, CLAIMS);

        assertThatThrownBy(() -> verifier.verify(TestTokens.writer())).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void issuerPattern_tenantPlaceholderMatchesOneLabel() {
        var pattern = NimbusTokenVerifier.issuerPattern("https://{tenant}.stg.eudistack.net/verifier");

        assertThat(pattern.matcher("https://red-logistica.stg.eudistack.net/verifier").matches()).isTrue();
        assertThat(pattern.matcher("https://a.b.stg.eudistack.net/verifier").matches()).isFalse();
        assertThat(pattern.matcher("https://x.stg.eudistack.net/verifier/extra").matches()).isFalse();
    }
}
