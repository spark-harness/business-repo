package com.spark.user.application.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.user.application.auth.RegisterOrLoginCommand;
import com.spark.user.application.auth.RegisterOrLoginResult;
import com.spark.user.application.auth.RegisterOrLoginUseCase;
import com.spark.user.infrastructure.auth.FixedVerificationCodeVerifier;
import com.spark.user.infrastructure.auth.InMemoryUserRepository;
import org.junit.jupiter.api.Test;

class SetUserEnabledUseCaseTest {
    private final InMemoryUserRepository userRepository = new InMemoryUserRepository();
    private final RegisterOrLoginUseCase registerOrLoginUseCase =
            new RegisterOrLoginUseCase(userRepository, new FixedVerificationCodeVerifier());
    private final SetUserEnabledUseCase setUserEnabledUseCase = new SetUserEnabledUseCase(userRepository);

    @Test
    void setEnabled_whenDisablingExistingUser_shouldDisableUser() {
        RegisterOrLoginResult user = registerOrLoginUseCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));

        SetUserEnabledResult result = setUserEnabledUseCase.setEnabled(
                new SetUserEnabledCommand(user.userId(), false));

        assertThat(result.userId()).isEqualTo(user.userId());
        assertThat(result.enabled()).isFalse();
    }

    @Test
    void setEnabled_whenRestoringExistingUser_shouldRestoreUser() {
        RegisterOrLoginResult user = registerOrLoginUseCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));
        setUserEnabledUseCase.setEnabled(new SetUserEnabledCommand(user.userId(), false));

        SetUserEnabledResult result = setUserEnabledUseCase.setEnabled(
                new SetUserEnabledCommand(user.userId(), true));

        assertThat(result.userId()).isEqualTo(user.userId());
        assertThat(result.enabled()).isTrue();
    }

    @Test
    void setEnabled_whenUserIdIsBlank_shouldRejectRequest() {
        assertThatThrownBy(() -> setUserEnabledUseCase.setEnabled(
                        new SetUserEnabledCommand("   ", false)))
                .isInstanceOf(InvalidProfileRequestException.class)
                .hasMessage("user id must not be blank");
    }

    @Test
    void setEnabled_whenUserDoesNotExist_shouldRejectRequest() {
        assertThatThrownBy(() -> setUserEnabledUseCase.setEnabled(
                        new SetUserEnabledCommand("user_missing", false)))
                .isInstanceOf(UserProfileNotFoundException.class)
                .hasMessage("user profile not found");
    }
}
