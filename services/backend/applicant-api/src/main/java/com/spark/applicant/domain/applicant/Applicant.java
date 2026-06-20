package com.spark.applicant.domain.applicant;

import java.time.Instant;

public record Applicant(String applicantId, PhoneNumber phoneNumber, Instant createdAt, Instant updatedAt) {}
