package com.spark.applicant.infrastructure.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.applicant.application.auth.ApplicantAuthException;
import com.spark.applicant.application.auth.SendOtpResult;
import com.spark.applicant.application.auth.TokenRecord;
import com.spark.applicant.domain.applicant.OtpChallenge;
import com.spark.applicant.domain.applicant.PhoneNumber;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class RedisAuthRepositoryTest {
    @Test
    void otpChallengeRepository_shouldPersistChallengeAndCooldownWithTtl() {
        FakeExpiringKeyValueStore store = new FakeExpiringKeyValueStore(Instant.parse("2026-06-20T00:00:00Z"));
        RedisOtpChallengeRepository repository = new RedisOtpChallengeRepository(store, "applicant-api");
        PhoneNumber phoneNumber = new PhoneNumber("+852", "91234567");
        OtpChallenge challenge = new OtpChallenge(
                "otp_1",
                phoneNumber,
                "hash",
                store.now().plus(Duration.ofMinutes(5)),
                store.now().plus(Duration.ofSeconds(60)),
                0,
                null,
                false);

        repository.save(challenge);

        assertThat(repository.find("otp_1")).contains(challenge);
        assertThat(repository.findActiveCooldown(phoneNumber, store.now())).contains(challenge);

        store.advance(Duration.ofSeconds(61));
        assertThat(repository.findActiveCooldown(phoneNumber, store.now())).isEmpty();
    }

    @Test
    void sessionTokenStore_shouldExpireRefreshToken() {
        FakeExpiringKeyValueStore store = new FakeExpiringKeyValueStore(Instant.parse("2026-06-20T00:00:00Z"));
        RedisSessionTokenStore tokenStore = new RedisSessionTokenStore(store, "applicant-api");
        TokenRecord token = new TokenRecord("refresh-token", "applicant_1", store.now().plus(Duration.ofHours(1)));

        tokenStore.saveRefreshToken(token);

        assertThat(tokenStore.findRefreshToken("refresh-token", store.now())).contains(token);
        store.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(tokenStore.findRefreshToken("refresh-token", store.now())).isEmpty();
    }

    @Test
    void idempotencyRepository_whenSameKeyDifferentFingerprint_shouldRejectConflict() {
        FakeExpiringKeyValueStore store = new FakeExpiringKeyValueStore(Instant.parse("2026-06-20T00:00:00Z"));
        RedisIdempotencyRepository repository = new RedisIdempotencyRepository(store, "applicant-api");
        SendOtpResult result = new SendOtpResult("otp_1", Duration.ofMinutes(5), Duration.ofSeconds(60));

        repository.save("send_otp", "idem-1", "+852:91234567", result, Duration.ofHours(1));

        assertThat(repository.find("send_otp", "idem-1", "+852:91234567", SendOtpResult.class)).contains(result);
        assertThatThrownBy(() -> repository.find("send_otp", "idem-1", "+852:99999999", SendOtpResult.class))
                .isInstanceOf(ApplicantAuthException.class)
                .hasMessage("idempotency_key_conflict");
    }
}
