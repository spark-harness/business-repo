package com.spark.user.application.auth.port;

import com.spark.user.domain.UserAccount;

public interface UserRepository {
    RegisterOrLoginOutcome findOrCreateByMobile(String mobile);

    UserAccount updateUsername(String userId, String username);

    record RegisterOrLoginOutcome(UserAccount user, boolean newUser) {}
}
