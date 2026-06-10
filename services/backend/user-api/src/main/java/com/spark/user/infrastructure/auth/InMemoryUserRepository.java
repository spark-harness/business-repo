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
    private final Map<String, UserAccount> usersById = new ConcurrentHashMap<>();

    @Override
    public RegisterOrLoginOutcome findOrCreateByMobile(String mobile) {
        UserAccount existing = usersByMobile.get(mobile);
        if (existing != null) {
            return new RegisterOrLoginOutcome(existing, false);
        }

        UserAccount created = new UserAccount("user_" + UUID.randomUUID(), mobile, "", true);
        UserAccount previous = usersByMobile.putIfAbsent(mobile, created);
        if (previous != null) {
            return new RegisterOrLoginOutcome(previous, false);
        }
        usersById.put(created.userId(), created);
        return new RegisterOrLoginOutcome(created, true);
    }

    @Override
    public UserAccount updateUsername(String userId, String username) {
        UserAccount updated = usersById.computeIfPresent(
                userId,
                (ignored, existing) -> new UserAccount(existing.userId(), existing.mobile(), username, existing.enabled()));
        if (updated != null) {
            usersByMobile.put(updated.mobile(), updated);
        }
        return updated;
    }

    @Override
    public UserAccount updateEnabled(String userId, boolean enabled) {
        UserAccount updated = usersById.computeIfPresent(
                userId,
                (ignored, existing) -> new UserAccount(existing.userId(), existing.mobile(), existing.username(), enabled));
        if (updated != null) {
            usersByMobile.put(updated.mobile(), updated);
        }
        return updated;
    }
}
