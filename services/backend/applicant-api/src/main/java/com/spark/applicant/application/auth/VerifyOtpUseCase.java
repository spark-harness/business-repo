package com.spark.applicant.application.auth;

import com.spark.applicant.application.auth.port.ApplicantRepository;
import com.spark.applicant.application.auth.port.IdempotencyRepository;
import com.spark.applicant.application.auth.port.OtpChallengeRepository;
import com.spark.applicant.application.auth.port.SessionTokenStore;
import com.spark.applicant.application.auth.port.TokenService;
import com.spark.applicant.domain.applicant.Applicant;
import com.spark.applicant.domain.applicant.OtpChallenge;
import com.spark.common.spring.cleanarchitecture.annotation.UseCase;
import java.time.Clock;
import java.time.Instant;

@UseCase
public class VerifyOtpUseCase {
    private final OtpChallengeRepository otpChallengeRepository;
    private final ApplicantRepository applicantRepository;
    private final SessionTokenStore tokenStore;
    private final IdempotencyRepository idempotencyRepository;
    private final TokenService tokenService;
    private final AuthPolicy policy;
    private final Clock clock;

    public VerifyOtpUseCase(
            OtpChallengeRepository otpChallengeRepository,
            ApplicantRepository applicantRepository,
            SessionTokenStore tokenStore,
            IdempotencyRepository idempotencyRepository,
            TokenService tokenService,
            AuthPolicy policy,
            Clock clock) {
        this.otpChallengeRepository = otpChallengeRepository;
        this.applicantRepository = applicantRepository;
        this.tokenStore = tokenStore;
        this.idempotencyRepository = idempotencyRepository;
        this.tokenService = tokenService;
        this.policy = policy;
        this.clock = clock;
    }

    public VerifyOtpResult verifyOtp(VerifyOtpCommand command) {
        requireIdempotencyKey(command.idempotencyKey());
        String fingerprint = CodeHasher.normalize(command.challengeId()) + ":" + CodeHasher.normalize(command.code());
        return idempotencyRepository
                .find("verify_otp", command.idempotencyKey().trim(), fingerprint, VerifyOtpResult.class)
                .orElseGet(() -> verify(command, fingerprint));
    }

    private VerifyOtpResult verify(VerifyOtpCommand command, String fingerprint) {
        Instant now = clock.instant();
        OtpChallenge challenge = otpChallengeRepository
                .find(CodeHasher.normalize(command.challengeId()))
                .orElseThrow(() -> new ApplicantAuthException("otp_code_expired"));
        if (challenge.isExpired(now)) {
            throw new ApplicantAuthException("otp_code_expired");
        }
        if (challenge.isLocked(now)) {
            throw new ApplicantAuthException("otp_too_many_attempts");
        }
        if (!challenge.codeHash().equals(CodeHasher.hash(command.code()))) {
            recordFailedAttempt(challenge, now);
        }

        OtpChallenge verified = challenge.markVerified();
        otpChallengeRepository.save(verified);
        Applicant applicant = applicantRepository.findOrCreateByPhoneNumber(challenge.phoneNumber());
        String accessToken = tokenService.issueAccessToken(applicant.applicantId());
        String refreshToken = tokenService.issueRefreshToken(applicant.applicantId());
        tokenStore.saveRefreshToken(new TokenRecord(refreshToken, applicant.applicantId(), now.plus(policy.refreshTokenTtl())));
        VerifyOtpResult result = new VerifyOtpResult(
                accessToken,
                refreshToken,
                applicant.applicantId(),
                policy.accessTokenTtl(),
                policy.refreshTokenTtl());
        idempotencyRepository.save("verify_otp", command.idempotencyKey().trim(), fingerprint, result, policy.idempotencyTtl());
        return result;
    }

    private void recordFailedAttempt(OtpChallenge challenge, Instant now) {
        int attempts = challenge.failedAttempts() + 1;
        if (attempts >= policy.maxOtpAttempts()) {
            otpChallengeRepository.save(challenge.withFailedAttempts(attempts).lockedUntil(now.plus(policy.lockDuration())));
            throw new ApplicantAuthException("otp_too_many_attempts");
        }
        otpChallengeRepository.save(challenge.withFailedAttempts(attempts));
        throw new ApplicantAuthException("otp_code_invalid");
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (CodeHasher.normalize(idempotencyKey).isEmpty()) {
            throw new ApplicantAuthException("idempotency_key_required");
        }
    }
}
