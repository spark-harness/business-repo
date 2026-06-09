package com.spark.user.application.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.user.application.auth.RegisterOrLoginCommand;
import com.spark.user.application.auth.RegisterOrLoginResult;
import com.spark.user.application.auth.RegisterOrLoginUseCase;
import com.spark.user.infrastructure.auth.FixedVerificationCodeVerifier;
import com.spark.user.infrastructure.auth.InMemoryUserRepository;
import org.junit.jupiter.api.Test;

class UpdateUsernameUseCaseTest {
    private final InMemoryUserRepository userRepository = new InMemoryUserRepository();
    private final RegisterOrLoginUseCase registerOrLoginUseCase =
            new RegisterOrLoginUseCase(userRepository, new FixedVerificationCodeVerifier());
    private final UpdateUsernameUseCase updateUsernameUseCase = new UpdateUsernameUseCase(userRepository);

    @Test
    void updateUsername_whenUserExists_shouldUpdateUsername() {
        RegisterOrLoginResult user = registerOrLoginUseCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));

        UpdateUsernameResult result = updateUsernameUseCase.updateUsername(
                new UpdateUsernameCommand(user.userId(), " Alice "));

        assertThat(result.userId()).isEqualTo(user.userId());
        assertThat(result.username()).isEqualTo("Alice");
    }

    @Test
    void updateUsername_whenUsernameIsBlank_shouldRejectRequest() {
        RegisterOrLoginResult user = registerOrLoginUseCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));

        assertThatThrownBy(() -> updateUsernameUseCase.updateUsername(
                        new UpdateUsernameCommand(user.userId(), "   ")))
                .isInstanceOf(InvalidProfileRequestException.class)
                .hasMessage("username must not be blank");
    }

    @Test
    void updateUsername_whenUserIdIsBlank_shouldRejectRequest() {
        assertThatThrownBy(() -> updateUsernameUseCase.updateUsername(
                        new UpdateUsernameCommand("   ", "Alice")))
                .isInstanceOf(InvalidProfileRequestException.class)
                .hasMessage("user id must not be blank");
    }

    @Test
    void updateUsername_whenUserDoesNotExist_shouldRejectRequest() {
        assertThatThrownBy(() -> updateUsernameUseCase.updateUsername(
                        new UpdateUsernameCommand("user_missing", "Alice")))
                .isInstanceOf(UserProfileNotFoundException.class)
                .hasMessage("user profile not found");
    }
}
