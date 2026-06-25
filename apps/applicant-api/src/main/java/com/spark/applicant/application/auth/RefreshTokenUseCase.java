package com.spark.applicant.application.auth;

import com.spark.applicant.application.auth.port.IdempotencyRepository;
import com.spark.applicant.application.auth.port.SessionTokenStore;
import com.spark.applicant.application.auth.port.TokenService;
import com.spark.common.spring.cleanarchitecture.annotation.UseCase;
import java.time.Clock;

@UseCase
public class RefreshTokenUseCase {
    private final SessionTokenStore tokenStore;
    private final IdempotencyRepository idempotencyRepository;
    private final TokenService tokenService;
    private final AuthPolicy policy;
    private final Clock clock;

    public RefreshTokenUseCase(
            SessionTokenStore tokenStore,
            IdempotencyRepository idempotencyRepository,
            TokenService tokenService,
            AuthPolicy policy,
            Clock clock) {
        this.tokenStore = tokenStore;
        this.idempotencyRepository = idempotencyRepository;
        this.tokenService = tokenService;
        this.policy = policy;
        this.clock = clock;
    }

    public RefreshTokenResult refreshToken(RefreshTokenCommand command) {
        requireIdempotencyKey(command.idempotencyKey());
        String refreshToken = CodeHasher.normalize(command.refreshToken());
        if (refreshToken.isEmpty()) {
            throw new ApplicantAuthException("token_invalid");
        }
        return idempotencyRepository
                .find("refresh_token", command.idempotencyKey().trim(), refreshToken, RefreshTokenResult.class)
                .orElseGet(() -> refresh(command, refreshToken));
    }

    private RefreshTokenResult refresh(RefreshTokenCommand command, String refreshToken) {
        TokenRecord token = tokenStore
                .findRefreshToken(refreshToken, clock.instant())
                .orElseThrow(() -> new ApplicantAuthException("token_expired"));
        RefreshTokenResult result =
                new RefreshTokenResult(tokenService.issueAccessToken(token.applicantId()), policy.accessTokenTtl());
        idempotencyRepository.save("refresh_token", command.idempotencyKey().trim(), refreshToken, result, policy.idempotencyTtl());
        return result;
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (CodeHasher.normalize(idempotencyKey).isEmpty()) {
            throw new ApplicantAuthException("idempotency_key_required");
        }
    }
}
