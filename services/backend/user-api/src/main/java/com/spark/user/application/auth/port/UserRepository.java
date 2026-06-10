package com.spark.user.application.auth.port;

import com.spark.user.domain.UserAccount;

public interface UserRepository {
    RegisterOrLoginOutcome findOrCreateByMobile(String mobile);

    UserAccount updateUsername(String userId, String username);

    UserAccount updateEnabled(String userId, boolean enabled);

    record RegisterOrLoginOutcome(UserAccount user, boolean newUser) {}
}
