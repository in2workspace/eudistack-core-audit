package com.eudistack.audit.infrastructure.security;

import com.eudistack.audit.domain.exception.InvalidTokenException;
import com.eudistack.audit.domain.exception.ServiceUnavailableException;
import com.eudistack.audit.domain.model.ActorKind;
import com.eudistack.audit.domain.model.Caller;
import com.eudistack.audit.domain.model.Power;
import com.eudistack.audit.infrastructure.config.AuditProperties;
import com.eudistack.audit.support.TestTokens;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NimbusTokenVerifierTest {

    private static final CallerClaims CLAIMS = new CallerClaims(List.of("-stg", "-dev", "-pre"));

    private static NimbusTokenVerifier verifier(List<String> audiences) throws ParseException {
        AuditProperties.Security security = new AuditProperties.Security();
        security.setTrustedIssuers(List.of(TestTokens.ISSUER_TEMPLATE));
        security.setAudience(audiences);
        JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(JWKSet.parse(TestTokens.publicJwks()));
        return new NimbusTokenVerifier(jwks, security, CLAIMS);
    }

    @Test
    void verify_systemToken_isSystemWithPowersAndOrganization() throws Exception {
        Caller caller = verifier(List.of()).verify(TestTokens.writer(), null);

        assertThat(caller.kind()).isEqualTo(ActorKind.SYSTEM);
        assertThat(caller.subject()).isEqualTo("issuer-client");
        assertThat(caller.organization()).isEqualTo("VATES-EUDISTACK");
        assertThat(caller.tenant()).isEqualTo("platform");
        assertThat(caller.powers()).containsExactly(new Power("organization", "EUDISTACK", "Audit", List.of("Write")));
        assertThat(caller.name()).isNull();
    }

    @Test
    void verify_personToken_isPersonWithNameAndEmail() throws Exception {
        Caller caller = verifier(List.of()).verify(TestTokens.tenantAuditor("red-logistica"), null);

        assertThat(caller.kind()).isEqualTo(ActorKind.PERSON);
        assertThat(caller.name()).isEqualTo("Ana Pérez");
        assertThat(caller.email()).isEqualTo("auditor-red-logistica@example.org");
        assertThat(caller.tenant()).isEqualTo("red-logistica");
    }

    @Test
    void verify_tenantWithEnvironmentSuffix_isNormalised() throws Exception {
        String token = TestTokens.token().person("p").tenant("Sandbox-STG").issuer("sandbox-stg").sign();

        assertThat(verifier(List.of()).verify(token, null).tenant()).isEqualTo("sandbox");
    }

    @Test
    void verify_foreignSignature_isInvalid() throws Exception {
        NimbusTokenVerifier verifier = verifier(List.of());
        String token = TestTokens.token().person("p").tenant("sandbox").signedWithForeignKey().sign();

        assertThatThrownBy(() -> verifier.verify(token, null)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_expired_isInvalid() throws Exception {
        NimbusTokenVerifier verifier = verifier(List.of());
        String token = TestTokens.token().person("p").tenant("sandbox")
                .expiresAt(Instant.now().minus(Duration.ofMinutes(5))).sign();

        assertThatThrownBy(() -> verifier.verify(token, null)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_untrustedIssuer_isInvalid() throws Exception {
        NimbusTokenVerifier verifier = verifier(List.of());
        String token = TestTokens.token().person("p").tenant("sandbox").issuer("evil.example.com/x").sign();

        assertThatThrownBy(() -> verifier.verify(token, null)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_notAJwt_isInvalid() throws Exception {
        NimbusTokenVerifier verifier = verifier(List.of());

        assertThatThrownBy(() -> verifier.verify("not-a-token", null)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_audienceConfigured_requiresIt() throws Exception {
        NimbusTokenVerifier verifier = verifier(List.of("audit-api"));
        String right = TestTokens.token().person("p").tenant("sandbox").audience("audit-api").sign();
        String missing = TestTokens.token().person("p").tenant("sandbox").sign();
        String other = TestTokens.token().person("p").tenant("sandbox").audience("issuer-api").sign();

        assertThat(verifier.verify(right, null)).isNotNull();
        assertThatThrownBy(() -> verifier.verify(missing, null)).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> verifier.verify(other, null)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verify_jwksUnavailable_isServiceUnavailable() {
        AuditProperties.Security security = new AuditProperties.Security();
        security.setTrustedIssuers(List.of(TestTokens.ISSUER_TEMPLATE));
        JWKSource<SecurityContext> down = (JWKSelector selector, SecurityContext context) -> {
            throw new KeySourceException("connection refused");
        };
        NimbusTokenVerifier verifier = new NimbusTokenVerifier(down, security, CLAIMS);
        String token = TestTokens.writer();

        assertThatThrownBy(() -> verifier.verify(token, null)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void issuerPattern_tenantPlaceholderMatchesOneLabel() {
        var pattern = NimbusTokenVerifier.issuerPattern("https://{tenant}.stg.eudistack.net/verifier");

        assertThat(pattern.matcher("https://red-logistica.stg.eudistack.net/verifier").matches()).isTrue();
        assertThat(pattern.matcher("https://a.b.stg.eudistack.net/verifier").matches()).isFalse();
        assertThat(pattern.matcher("https://x.stg.eudistack.net/verifier/extra").matches()).isFalse();
    }

    @Nested
    @DisplayName("audience with the tenant marker")
    class AudienceWithTheTenantMarker {

        private final List<String> audiences = List.of("audit-api", "vc-auth-client-{tenant}");

        private String tokenFor(String audience) {
            return TestTokens.token().person("p").tenant("red-logistica").audience(audience).sign();
        }

        @ParameterizedTest(name = "audience {0} on host tenant {1} is accepted")
        @CsvSource({
                "vc-auth-client-red-logistica, red-logistica",
                "audit-api, red-logistica",
                "audit-api,"})
        @DisplayName("verify_listedAudience_isAccepted")
        void verify_listedAudience_isAccepted(String audience, String hostTenant) throws Exception {
            // Arrange
            NimbusTokenVerifier verifier = verifier(audiences);
            String token = tokenFor(audience);

            // Act
            Caller caller = verifier.verify(token, hostTenant);

            // Assert
            assertThat(caller.tenant()).isEqualTo("red-logistica");
        }

        @ParameterizedTest(name = "audience {0} on host tenant {1} is invalid")
        @CsvSource({
                "vc-auth-client-red-logistica, sandbox",
                "vc-auth-client-red-logistica,",
                "vc-auth-client-{tenant},",
                "other, red-logistica"})
        @DisplayName("verify_audienceNotListedForTheHost_isInvalid")
        void verify_audienceNotListedForTheHost_isInvalid(String audience, String hostTenant) throws Exception {
            // Arrange
            NimbusTokenVerifier verifier = verifier(audiences);
            String token = tokenFor(audience);

            // Act + Assert
            assertThatThrownBy(() -> verifier.verify(token, hostTenant)).isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("verify_blankConfiguredValues_areNotAnAudienceCheck")
        void verify_blankConfiguredValues_areNotAnAudienceCheck() throws Exception {
            // Arrange
            NimbusTokenVerifier verifier = verifier(List.of(" ", ""));
            String token = TestTokens.token().person("p").tenant("red-logistica").sign();

            // Act
            Caller caller = verifier.verify(token, "red-logistica");

            // Assert
            assertThat(caller).isNotNull();
        }
    }
}
