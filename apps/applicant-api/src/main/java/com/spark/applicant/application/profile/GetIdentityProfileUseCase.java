package com.spark.applicant.application.profile;

public class GetIdentityProfileUseCase {
    private final IdentityProfileRepository repository;

    public GetIdentityProfileUseCase(IdentityProfileRepository repository) {
        this.repository = repository;
    }

    public GetIdentityProfileResult get(GetIdentityProfileCommand command) {
        return repository.findByApplicantId(command.applicantId())
                .map(GetIdentityProfileResult::found)
                .orElseGet(GetIdentityProfileResult::emptyResult);
    }
}
