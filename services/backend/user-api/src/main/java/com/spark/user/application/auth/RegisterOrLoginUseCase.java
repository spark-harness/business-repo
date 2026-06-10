package com.spark.user.application.auth;

import com.spark.common.spring.cleanarchitecture.annotation.UseCase;
import com.spark.user.application.auth.port.UserRepository;
import com.spark.user.application.auth.port.VerificationCodeVerifier;
import com.spark.user.domain.UserAccount;
import java.util.regex.Pattern;

@UseCase
public class RegisterOrLoginUseCase {
    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private final UserRepository userRepository;
    private final VerificationCodeVerifier verificationCodeVerifier;

    public RegisterOrLoginUseCase(
            UserRepository userRepository,
            VerificationCodeVerifier verificationCodeVerifier) {
        this.userRepository = userRepository;
        this.verificationCodeVerifier = verificationCodeVerifier;
    }

    public RegisterOrLoginResult registerOrLogin(RegisterOrLoginCommand command) {
        String mobile = normalize(command.mobile());
        String verificationCode = normalize(command.verificationCode());

        if (!MOBILE_PATTERN.matcher(mobile).matches()) {
            throw new InvalidAuthRequestException("mobile format is invalid");
        }
        if (verificationCode.isEmpty()) {
            throw new InvalidAuthRequestException("verification code must not be blank");
        }
        if (!verificationCodeVerifier.verify(mobile, verificationCode)) {
            throw new InvalidAuthRequestException("verification code is invalid");
        }

        UserRepository.RegisterOrLoginOutcome outcome = userRepository.findOrCreateByMobile(mobile);
        UserAccount user = outcome.user();
        if (!user.enabled()) {
            throw new UserLoginDisabledException("user login is disabled");
        }
        return new RegisterOrLoginResult(user.userId(), outcome.newUser());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
