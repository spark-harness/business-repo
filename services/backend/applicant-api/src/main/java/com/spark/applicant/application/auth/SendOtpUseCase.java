package com.spark.applicant.application.auth;

import com.spark.applicant.application.auth.port.IdempotencyRepository;
import com.spark.applicant.application.auth.port.OtpChallengeRepository;
import com.spark.applicant.application.auth.port.SmsCodeSender;
import com.spark.applicant.domain.applicant.OtpChallenge;
import com.spark.applicant.domain.applicant.PhoneNumber;
import com.spark.common.spring.cleanarchitecture.annotation.UseCase;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@UseCase
public class SendOtpUseCase {
    private final OtpChallengeRepository otpChallengeRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final SmsCodeSender smsCodeSender;
    private final AuthPolicy policy;
    private final Clock clock;

    public SendOtpUseCase(
            OtpChallengeRepository otpChallengeRepository,
            IdempotencyRepository idempotencyRepository,
            SmsCodeSender smsCodeSender,
            AuthPolicy policy,
            Clock clock) {
        this.otpChallengeRepository = otpChallengeRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.smsCodeSender = smsCodeSender;
        this.policy = policy;
        this.clock = clock;
    }

    public SendOtpResult sendOtp(SendOtpCommand command) {
        requireIdempotencyKey(command.idempotencyKey());
        PhoneNumber phoneNumber = phoneNumber(command.countryCode(), command.phone());
        String fingerprint = phoneNumber.stableKey();
        return idempotencyRepository
                .find("send_otp", command.idempotencyKey().trim(), fingerprint, SendOtpResult.class)
                .orElseGet(() -> createChallenge(command, phoneNumber, fingerprint));
    }

    private SendOtpResult createChallenge(SendOtpCommand command, PhoneNumber phoneNumber, String fingerprint) {
        Instant now = clock.instant();
        if (otpChallengeRepository.findActiveCooldown(phoneNumber, now).isPresent()) {
            throw new ApplicantAuthException("otp_cooldown_active");
        }

        String code = smsCodeSender.sendCode(phoneNumber);
        OtpChallenge challenge = new OtpChallenge(
                "otp_" + UUID.randomUUID(),
                phoneNumber,
                CodeHasher.hash(code),
                now.plus(policy.otpTtl()),
                now.plus(policy.resendCooldown()),
                0,
                null,
                false);
        otpChallengeRepository.save(challenge);
        SendOtpResult result = new SendOtpResult(
                challenge.challengeId(),
                policy.otpTtl(),
                policy.resendCooldown());
        idempotencyRepository.save("send_otp", command.idempotencyKey().trim(), fingerprint, result, policy.idempotencyTtl());
        return result;
    }

    private PhoneNumber phoneNumber(String countryCode, String phone) {
        PhoneNumber phoneNumber = new PhoneNumber(countryCode, phone);
        if (!policy.supportedCountryCode().equals(phoneNumber.countryCode())) {
            throw new ApplicantAuthException("unsupported_country");
        }
        if (phoneNumber.phone().isEmpty()) {
            throw new ApplicantAuthException("phone_required");
        }
        return phoneNumber;
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (CodeHasher.normalize(idempotencyKey).isEmpty()) {
            throw new ApplicantAuthException("idempotency_key_required");
        }
    }
}
