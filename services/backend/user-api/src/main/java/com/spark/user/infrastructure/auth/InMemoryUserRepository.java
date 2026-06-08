package com.spark.user.infrastructure.auth;

import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import com.spark.user.application.auth.port.UserRepository;
import com.spark.user.domain.UserAccount;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@InfrastructureAdapter
public class InMemoryUserRepository implements UserRepository {
    private final Map<String, UserAccount> usersByMobile = new ConcurrentHashMap<>();

    @Override
    public RegisterOrLoginOutcome findOrCreateByMobile(String mobile) {
        UserAccount existing = usersByMobile.get(mobile);
        if (existing != null) {
            return new RegisterOrLoginOutcome(existing, false);
        }

        UserAccount created = new UserAccount("user_" + UUID.randomUUID(), mobile);
        UserAccount previous = usersByMobile.putIfAbsent(mobile, created);
        if (previous != null) {
            return new RegisterOrLoginOutcome(previous, false);
        }
        return new RegisterOrLoginOutcome(created, true);
    }
}
