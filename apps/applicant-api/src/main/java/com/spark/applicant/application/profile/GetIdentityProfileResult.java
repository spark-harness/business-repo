package com.spark.applicant.application.profile;

import com.spark.applicant.domain.profile.IdentityProfile;

public record GetIdentityProfileResult(boolean empty, IdentityProfile profile) {
    public static GetIdentityProfileResult emptyResult() {
        return new GetIdentityProfileResult(true, null);
    }

    public static GetIdentityProfileResult found(IdentityProfile profile) {
        return new GetIdentityProfileResult(false, profile);
    }
}
