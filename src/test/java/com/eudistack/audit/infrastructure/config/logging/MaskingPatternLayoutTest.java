package com.eudistack.audit.infrastructure.config.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.eudistack.audit.infrastructure.config.logging.MaskingPatternLayout.REPLACEMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaskingPatternLayoutTest {

    private static final String JWT_SAMPLE =
            "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
    private static final String BEARER_TOKEN = "Bearer " + JWT_SAMPLE;

    private MaskingPatternLayout layout;

    @BeforeEach
    void setUp() {
        LoggerContext context = new LoggerContext();
        layout = new MaskingPatternLayout();
        layout.setContext(context);
        layout.setPattern("%msg");
        layout.start();
    }

    @AfterEach
    void tearDown() {
        layout.stop();
    }

    // ─── mask() – Edge Cases ─────────────────────────────────────────────────

    @Test
    void mask_NullInput_ReturnsNull() {
        // Arrange
        String input = null;

        // Act
        String result = MaskingPatternLayout.mask(input);

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void mask_EmptyString_ReturnsEmptyString() {
        // Arrange
        String input = "";

        // Act
        String result = MaskingPatternLayout.mask(input);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void mask_BlankString_ReturnsBlankString() {
        // Arrange
        String input = "   ";

        // Act
        String result = MaskingPatternLayout.mask(input);

        // Assert
        assertThat(result).isBlank();
    }

    // ─── mask() – Email ───────────────────────────────────────────────────────

    @Test
    void mask_StandaloneEmail_MasksEmail() {
        // Arrange
        String input = "admin@example.com";

        // Act
        String result = MaskingPatternLayout.mask(input);

        // Assert
        assertThat(result).isEqualTo(REPLACEMENT);
    }

    @Test
    void mask_EmailEmbeddedInSentence_MasksOnlyEmail() {
        // Arrange
        String input = "User admin@empresa.com authenticated successfully";

        // Act
        String result = MaskingPatternLayout.mask(input);

        // Assert
        assertThat(result)
                .isEqualTo("User " + REPLACEMENT + " authenticated successfully")
                .doesNotContain("admin@empresa.com");
    }

    // ─── mask() – JWT ─────────────────────────────────────────────────────────

    @Test
    void mask_StandaloneJwt_MasksJwt() {
        // Arrange – JWT_SAMPLE is a three-part eyJ… token

        // Act
        String result = MaskingPatternLayout.mask(JWT_SAMPLE);

        // Assert
        assertThat(result).isEqualTo(REPLACEMENT);
    }

    @Test
    void mask_JwtEmbeddedInLogMessage_MasksJwt() {
        // Arrange
        String input = "Validating token: " + JWT_SAMPLE + " for user 42";

        // Act
        String result = MaskingPatternLayout.mask(input);

        // Assert
        assertThat(result)
                .isEqualTo("Validating token: " + REPLACEMENT + " for user 42")
                .doesNotContain("eyJhbGciOiJSUzI1NiJ9");
    }

    // ─── mask() – Bearer Token ────────────────────────────────────────────────

    @Test
    void mask_BearerTokenHeader_PreservesBearerKeywordAndMasksValue() {
        // Arrange
        String input = "Authorization: " + BEARER_TOKEN;

        // Act
        String result = MaskingPatternLayout.mask(input);

        // Assert
        assertThat(result)
                .contains("Bearer " + REPLACEMENT)
                .doesNotContain(JWT_SAMPLE);
    }

    @Test
    void mask_BearerTokenLowerCase_MasksValue() {
        // Arrange
        String input = "header: bearer someOpaqueToken123";

        // Act
        String result = MaskingPatternLayout.mask(input);

        // Assert
        assertThat(result)
                .containsIgnoringCase("bearer " + REPLACEMENT)
                .doesNotContain("someOpaqueToken123");
    }

    // ─── mask() – Key=Value sensitive fields ─────────────────────────────────

    @ParameterizedTest
    @CsvSource(delimiterString = " | ", value = {
            "Sending email to john.doe+filter@mail.company.org | john.doe+filter@mail.company.org",
            "{\"tx_code\":\"ABC123\"} | ABC123",
            "{\"access_token\":\"someAccessTokenValue\"} | someAccessTokenValue",
            "{\"refresh_token\":\"someRefreshTokenValue\"} | someRefreshTokenValue",
            "{\"password\":\"SuperSecret123!\"} | SuperSecret123!",
            "{\"secret\":\"myAppSecret\"} | myAppSecret",
            "{\"client_secret\":\"oauth2ClientSecret\"} | oauth2ClientSecret",
            "POST /token password=p@ssw0rd&grant_type=password | p@ssw0rd",
            "secret = mySecretValue loggedBySystem | mySecretValue"
    })
    void mask_SensitiveValue_MasksValue(String input, String sensitiveValue) {
        assertThat(MaskingPatternLayout.mask(input))
                .contains(REPLACEMENT)
                .doesNotContain(sensitiveValue);
    }

    // ─── mask() – Realistic log scenarios ────────────────────────────────────

    @Test
    void mask_FullRealisticLogWithEmailAndBearerToken_MasksAllSensitiveData() {
        // Arrange
        String input = "User admin@empresa.com authenticated with token " + BEARER_TOKEN;

        // Act
        String result = MaskingPatternLayout.mask(input);

        // Assert
        assertThat(result)
                .contains("User " + REPLACEMENT)
                .contains("Bearer " + REPLACEMENT)
                .doesNotContain("admin@empresa.com")
                .doesNotContain(JWT_SAMPLE);
    }

    @Test
    void mask_JsonLogWithMultipleSensitiveFields_MasksAllFields() {
        // Arrange
        String input = "{\"user\":\"" + "alice@test.com" + "\","
                + "\"access_token\":\"tokenValue\","
                + "\"refresh_token\":\"refreshValue\","
                + "\"password\":\"secret123\"}";

        // Act
        String result = MaskingPatternLayout.mask(input);

        // Assert
        assertThat(result)
                .doesNotContain("alice@test.com")
                .doesNotContain("tokenValue")
                .doesNotContain("refreshValue")
                .doesNotContain("secret123");
        assertThat(result.chars().filter(c -> c == '*').count()).isGreaterThan(0L);
    }

    @Test
    void mask_TokenExchangeRequestLog_MasksTokensAndKeepsGrantType() {
        // Arrange
        String input = "Token exchange: grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
                + " access_token=eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.sig"
                + " refresh_token=rT_abc123";

        // Act
        String result = MaskingPatternLayout.mask(input);

        // Assert
        assertThat(result)
                .contains("grant_type=urn:ietf:params:oauth:grant-type:token-exchange")
                .doesNotContain("eyJhbGciOiJSUzI1NiJ9")
                .doesNotContain("rT_abc123");
    }

    // ─── doLayout() ───────────────────────────────────────────────────────────

    @Test
    void doLayout_MessageWithEmail_MasksEmail() {
        // Arrange
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn("Contact support@company.com for issues");

        // Act
        String result = layout.doLayout(event);

        // Assert
        assertThat(result)
                .contains(REPLACEMENT)
                .doesNotContain("support@company.com");
    }

    @Test
    void doLayout_MessageWithBearerToken_MasksBearerValue() {
        // Arrange
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn("Received Authorization: Bearer mySecretBearerToken");

        // Act
        String result = layout.doLayout(event);

        // Assert
        assertThat(result)
                .contains("Bearer " + REPLACEMENT)
                .doesNotContain("mySecretBearerToken");
    }

    @Test
    void doLayout_MessageWithNoSensitiveData_ReturnsFormattedMessageUnchanged() {
        // Arrange
        String plainMessage = "Scheduler triggered at fixed rate";
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(plainMessage);

        // Act
        String result = layout.doLayout(event);

        // Assert
        assertThat(result).isEqualTo(plainMessage);
    }

    // ─── Regression: false-positive fixes ────────────────────────────────────

    @Test
    void mask_PaddedJwtWithEqualsSign_MasksJwt() {
        // Arrange – base64url segments that include '=' padding characters
        String paddedJwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0=.SflKxwRJSMeKKF2QT4fw==";

        // Act
        String result = MaskingPatternLayout.mask(paddedJwt);

        // Assert
        assertThat(result)
                .isEqualTo(REPLACEMENT)
                .doesNotContain("eyJhbGciOiJSUzI1NiJ9");
    }

    @Test
    void mask_EyJPrefixWithoutValidTwoSeparators_NotMasked() {
        // Arrange – 'eyJ' with only one dot segment should NOT be treated as a JWT
        String notAJwt = "eyJ.onlyone";

        // Act
        String result = MaskingPatternLayout.mask(notAJwt);

        // Assert
        assertThat(result).isEqualTo(notAJwt);
    }

    @Test
    void mask_AuthorizationCodeInQueryString_MasksCodeKeepingOtherParams() {
        String result = MaskingPatternLayout.mask(
                "grant_type=authorization_code&code=AUTH-CODE-123&redirect_uri=https://app.example.com/cb");

        assertThat(result)
                .isEqualTo("grant_type=authorization_code&code=" + REPLACEMENT + "&redirect_uri=https://app.example.com/cb");
    }

    @Test
    void mask_CodeAsJsonField_MasksCode() {
        assertThat(MaskingPatternLayout.mask("{\"code\":\"482913\"}")).doesNotContain("482913");
    }

    @Test
    void mask_OtpAsKeyValue_MasksOtp() {
        assertThat(MaskingPatternLayout.mask("Verifying otp=482913 for tenant sandbox"))
                .isEqualTo("Verifying otp=" + REPLACEMENT + " for tenant sandbox");
    }

    @Test
    void mask_CamelCaseRefreshToken_MasksToken() {
        assertThat(MaskingPatternLayout.mask("{\"refreshToken\":\"rT_abc123\"}")).doesNotContain("rT_abc123");
    }

    @Test
    void mask_Uuid_IsLeftUnchanged() {
        String input = "Processing procedure 550e8400-e29b-41d4-a716-446655440000";

        assertThat(MaskingPatternLayout.mask(input)).isEqualTo(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Application started successfully on port 8080",
            "Processing request with correlationId=550e8400-e29b-41d4-a716-446655440000",
            "Calling external service at https://api.example.com/v1/resources",
            "grant_type=authorization_code&redirect_uri=https://app.example.com/cb",
            "Authentication strategy: passwordless via OTP",
            "{\"token_type\":\"Bearer\",\"expires_in\":3600}",
            "2025-01-01 12:00:00 INFO  [main] c.example.Service - Request processed in 45ms",
            "notsecret=sensitiveValue",
            "error_code=expired_code notsecret=value",
            "Failed to send a verification code: org.springframework.mail.MailSendException",
            "Failed to resolve name. status=Status{code=UNAVAILABLE, description=Unable to resolve host}",
            "{\"code\":\"invalid_request\"}",
            "eyJ.onlyone"
    })
    void mask_NoSensitiveData_ReturnsUnchanged(String input) {
        assertThat(MaskingPatternLayout.mask(input)).isEqualTo(input);
    }
}
