package com.spark.user.application.profile;

import com.spark.common.spring.cleanarchitecture.annotation.UseCase;
import com.spark.user.application.auth.port.UserRepository;
import com.spark.user.domain.UserAccount;

@UseCase
public class SetUserEnabledUseCase {
    private final UserRepository userRepository;

    public SetUserEnabledUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public SetUserEnabledResult setEnabled(SetUserEnabledCommand command) {
        String userId = normalize(command.userId());
        if (userId.isEmpty()) {
            throw new InvalidProfileRequestException("user id must not be blank");
        }

        UserAccount updated = userRepository.updateEnabled(userId, command.enabled());
        if (updated == null) {
            throw new UserProfileNotFoundException("user profile not found");
        }
        return new SetUserEnabledResult(updated.userId(), updated.enabled());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
