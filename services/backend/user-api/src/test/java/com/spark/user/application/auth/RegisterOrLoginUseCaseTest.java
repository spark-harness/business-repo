package com.spark.user.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.user.infrastructure.auth.FixedVerificationCodeVerifier;
import com.spark.user.infrastructure.auth.InMemoryUserRepository;
import org.junit.jupiter.api.Test;

class RegisterOrLoginUseCaseTest {
    private final InMemoryUserRepository userRepository = new InMemoryUserRepository();
    private final RegisterOrLoginUseCase useCase =
            new RegisterOrLoginUseCase(userRepository, new FixedVerificationCodeVerifier());

    @Test
    void registerOrLogin_whenMobileIsNew_shouldCreateUser() {
        RegisterOrLoginResult result = useCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));

        assertThat(result.userId()).isNotBlank();
        assertThat(result.newUser()).isTrue();
    }

    @Test
    void registerOrLogin_whenMobileExists_shouldReturnExistingUser() {
        RegisterOrLoginResult first = useCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));
        RegisterOrLoginResult second = useCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));

        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(second.newUser()).isFalse();
    }

    @Test
    void registerOrLogin_whenMobileIsInvalid_shouldRejectRequest() {
        RegisterOrLoginCommand command = new RegisterOrLoginCommand("12345", "123456");

        assertThatThrownBy(() -> useCase.registerOrLogin(command))
                .isInstanceOf(InvalidAuthRequestException.class)
                .hasMessage("mobile format is invalid");
    }

    @Test
    void registerOrLogin_whenVerificationCodeIsWrong_shouldRejectRequest() {
        RegisterOrLoginCommand command = new RegisterOrLoginCommand("13800138000", "000000");

        assertThatThrownBy(() -> useCase.registerOrLogin(command))
                .isInstanceOf(InvalidAuthRequestException.class)
                .hasMessage("verification code is invalid");
    }

    @Test
    void registerOrLogin_whenUserIsDisabled_shouldRejectLogin() {
        RegisterOrLoginResult user = useCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));
        userRepository.updateEnabled(user.userId(), false);

        assertThatThrownBy(() -> useCase.registerOrLogin(
                        new RegisterOrLoginCommand("13800138000", "123456")))
                .isInstanceOf(UserLoginDisabledException.class)
                .hasMessage("user login is disabled");
    }

    @Test
    void registerOrLogin_whenUserIsRestored_shouldReturnExistingUser() {
        RegisterOrLoginResult user = useCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));
        userRepository.updateEnabled(user.userId(), false);
        userRepository.updateEnabled(user.userId(), true);

        RegisterOrLoginResult result = useCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));

        assertThat(result.userId()).isEqualTo(user.userId());
        assertThat(result.newUser()).isFalse();
    }
}
