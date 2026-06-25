package com.spark.applicant.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.applicant.application.auth.port.SessionTokenStore;
import com.spark.applicant.infrastructure.auth.InMemoryApplicantRepository;
import com.spark.applicant.infrastructure.auth.InMemoryIdempotencyRepository;
import com.spark.applicant.infrastructure.auth.InMemoryOtpChallengeRepository;
import com.spark.applicant.infrastructure.auth.InMemorySessionTokenStore;
import com.spark.applicant.infrastructure.auth.SimpleTokenService;
import com.spark.applicant.infrastructure.auth.TestSmsCodeSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApplicantAuthUseCaseTest {
    private MutableClock clock;
    private InMemoryApplicantRepository applicantRepository;
    private InMemoryOtpChallengeRepository otpRepository;
    private SendOtpUseCase sendOtpUseCase;
    private VerifyOtpUseCase verifyOtpUseCase;
    private RefreshTokenUseCase refreshTokenUseCase;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-06-19T10:00:00Z"));
        applicantRepository = new InMemoryApplicantRepository(clock);
        otpRepository = new InMemoryOtpChallengeRepository(clock);
        SessionTokenStore tokenStore = new InMemorySessionTokenStore(clock);
        InMemoryIdempotencyRepository idempotencyRepository = new InMemoryIdempotencyRepository(clock);
        SimpleTokenService tokenService = new SimpleTokenService(clock);
        TestSmsCodeSender smsCodeSender = new TestSmsCodeSender("123456");
        AuthPolicy policy = AuthPolicy.defaults();

        sendOtpUseCase = new SendOtpUseCase(otpRepository, idempotencyRepository, smsCodeSender, policy, clock);
        verifyOtpUseCase = new VerifyOtpUseCase(
                otpRepository, applicantRepository, tokenStore, idempotencyRepository, tokenService, policy, clock);
        refreshTokenUseCase = new RefreshTokenUseCase(tokenStore, idempotencyRepository, tokenService, policy, clock);
    }

    @Test
    void sendOtp_whenHongKongPhoneIsValid_shouldCreateChallengeWithExpiryAndCooldown() {
        SendOtpResult result = sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-1"));

        assertThat(result.challengeId()).isNotBlank();
        assertThat(result.expiresIn()).isEqualTo(Duration.ofMinutes(5));
        assertThat(result.resendAfter()).isEqualTo(Duration.ofSeconds(60));
        assertThat(otpRepository.find(result.challengeId())).isPresent();
    }

    @Test
    void sendOtp_whenCountryIsUnsupported_shouldRejectWithoutCreatingChallenge() {
        assertThatThrownBy(() -> sendOtpUseCase.sendOtp(new SendOtpCommand("+86", "13800138000", "idem-send-1")))
                .isInstanceOf(ApplicantAuthException.class)
                .hasMessage("unsupported_country");

        assertThat(otpRepository.challengeCount()).isZero();
    }

    @Test
    void sendOtp_whenPhoneIsInCooldown_shouldRejectSecondSend() {
        sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-1"));

        assertThatThrownBy(() -> sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-2")))
                .isInstanceOf(ApplicantAuthException.class)
                .hasMessage("otp_cooldown_active");
    }

    @Test
    void sendOtp_whenIdempotencyKeyRepeats_shouldReplayFirstResult() {
        SendOtpResult first = sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-1"));
        SendOtpResult second = sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-1"));

        assertThat(second).isEqualTo(first);
        assertThat(otpRepository.challengeCount()).isEqualTo(1);
    }

    @Test
    void sendOtp_whenIdempotencyKeyIsBlank_shouldRejectRequest() {
        assertThatThrownBy(() -> sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", " ")))
                .isInstanceOf(ApplicantAuthException.class)
                .hasMessage("idempotency_key_required");
    }

    @Test
    void verifyOtp_whenCodeIsCorrect_shouldCreateApplicantAndIssueOneHourTokens() {
        SendOtpResult challenge = sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-1"));

        VerifyOtpResult result = verifyOtpUseCase.verifyOtp(
                new VerifyOtpCommand(challenge.challengeId(), "123456", "idem-verify-1"));

        assertThat(result.applicantId()).startsWith("applicant_");
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.expiresIn()).isEqualTo(Duration.ofHours(1));
        assertThat(result.refreshExpiresIn()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void verifyOtp_whenPhoneAlreadyHasApplicant_shouldReturnExistingApplicant() {
        SendOtpResult firstChallenge = sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-1"));
        VerifyOtpResult first = verifyOtpUseCase.verifyOtp(
                new VerifyOtpCommand(firstChallenge.challengeId(), "123456", "idem-verify-1"));
        clock.advance(Duration.ofSeconds(61));
        SendOtpResult secondChallenge = sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-2"));

        VerifyOtpResult second = verifyOtpUseCase.verifyOtp(
                new VerifyOtpCommand(secondChallenge.challengeId(), "123456", "idem-verify-2"));

        assertThat(second.applicantId()).isEqualTo(first.applicantId());
        assertThat(applicantRepository.count()).isEqualTo(1);
    }

    @Test
    void verifyOtp_whenChallengeIsExpired_shouldRejectRequest() {
        SendOtpResult challenge = sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-1"));
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThatThrownBy(() -> verifyOtpUseCase.verifyOtp(
                        new VerifyOtpCommand(challenge.challengeId(), "123456", "idem-verify-1")))
                .isInstanceOf(ApplicantAuthException.class)
                .hasMessage("otp_code_expired");
    }

    @Test
    void verifyOtp_whenCodeIsWrongFiveTimes_shouldLockChallenge() {
        SendOtpResult challenge = sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-1"));

        for (int i = 0; i < 4; i++) {
            int attempt = i;
            assertThatThrownBy(() -> verifyOtpUseCase.verifyOtp(
                            new VerifyOtpCommand(challenge.challengeId(), "000000", "idem-wrong-" + attempt)))
                    .isInstanceOf(ApplicantAuthException.class)
                    .hasMessage("otp_code_invalid");
        }

        assertThatThrownBy(() -> verifyOtpUseCase.verifyOtp(
                        new VerifyOtpCommand(challenge.challengeId(), "000000", "idem-wrong-5")))
                .isInstanceOf(ApplicantAuthException.class)
                .hasMessage("otp_too_many_attempts");
        assertThatThrownBy(() -> verifyOtpUseCase.verifyOtp(
                        new VerifyOtpCommand(challenge.challengeId(), "123456", "idem-after-lock")))
                .isInstanceOf(ApplicantAuthException.class)
                .hasMessage("otp_too_many_attempts");
    }

    @Test
    void verifyOtp_whenIdempotencyKeyRepeats_shouldReplayFirstResult() {
        SendOtpResult challenge = sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-1"));

        VerifyOtpResult first = verifyOtpUseCase.verifyOtp(
                new VerifyOtpCommand(challenge.challengeId(), "123456", "idem-verify-1"));
        VerifyOtpResult second = verifyOtpUseCase.verifyOtp(
                new VerifyOtpCommand(challenge.challengeId(), "123456", "idem-verify-1"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void refreshToken_whenRefreshTokenIsValid_shouldIssueAccessTokenWithoutRollingRefresh() {
        SendOtpResult challenge = sendOtpUseCase.sendOtp(new SendOtpCommand("+852", "91234567", "idem-send-1"));
        VerifyOtpResult login = verifyOtpUseCase.verifyOtp(
                new VerifyOtpCommand(challenge.challengeId(), "123456", "idem-verify-1"));
        clock.advance(Duration.ofMinutes(30));

        RefreshTokenResult refreshed = refreshTokenUseCase.refreshToken(
                new RefreshTokenCommand(login.refreshToken(), "idem-refresh-1"));

        assertThat(refreshed.accessToken()).isNotEqualTo(login.accessToken());
        assertThat(refreshed.expiresIn()).isEqualTo(Duration.ofHours(1));
        assertThat(refreshTokenUseCase.refreshToken(
                new RefreshTokenCommand(login.refreshToken(), "idem-refresh-1"))).isEqualTo(refreshed);
        clock.advance(Duration.ofMinutes(31));
        assertThatThrownBy(() -> refreshTokenUseCase.refreshToken(
                        new RefreshTokenCommand(login.refreshToken(), "idem-refresh-2")))
                .isInstanceOf(ApplicantAuthException.class)
                .hasMessage("token_expired");
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
