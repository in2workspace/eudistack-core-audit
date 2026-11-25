package com.eudistack.audit.domain.port.out;

import com.eudistack.audit.domain.model.Caller;

/**
 * Verifies a Verifier access token: signature against the JWKS, issuer, {@code exp}, {@code nbf} and,
 * when configured, {@code aud}.
 */
public interface TokenVerifier {

    /**
     * @param hostTenant tenant of the request host, which resolves the tenant marker of the accepted audiences;
     *                   {@code null} when the request has none (internal host, recovery)
     * @throws com.eudistack.audit.domain.exception.InvalidTokenException      the token is not valid
     * @throws com.eudistack.audit.domain.exception.ServiceUnavailableException the JWKS is not available
     */
    Caller verify(String bearerToken, String hostTenant);
}
