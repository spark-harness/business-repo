package com.spark.user.application.auth;

public record RegisterOrLoginCommand(String mobile, String verificationCode) {}
