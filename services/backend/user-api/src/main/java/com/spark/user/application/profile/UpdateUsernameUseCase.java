package com.spark.user.application.profile;

import com.spark.common.spring.cleanarchitecture.annotation.UseCase;
import com.spark.user.application.auth.port.UserRepository;
import com.spark.user.domain.UserAccount;

@UseCase
public class UpdateUsernameUseCase {
    private final UserRepository userRepository;

    public UpdateUsernameUseCase(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UpdateUsernameResult updateUsername(UpdateUsernameCommand command) {
        String userId = normalize(command.userId());
        String username = normalize(command.username());

        if (userId.isEmpty()) {
            throw new InvalidProfileRequestException("user id must not be blank");
        }
        if (username.isEmpty()) {
            throw new InvalidProfileRequestException("username must not be blank");
        }

        UserAccount updated = userRepository.updateUsername(userId, username);
        if (updated == null) {
            throw new UserProfileNotFoundException("user profile not found");
        }
        return new UpdateUsernameResult(updated.userId(), updated.username());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
