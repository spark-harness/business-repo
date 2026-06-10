package com.spark.user.application.profile;

public record SetUserEnabledCommand(String userId, boolean enabled) {}
