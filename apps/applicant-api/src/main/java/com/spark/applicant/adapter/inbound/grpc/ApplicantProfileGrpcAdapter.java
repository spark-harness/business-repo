package com.spark.applicant.adapter.inbound.grpc;

import com.spark.applicant.application.profile.GetIdentityProfileCommand;
import com.spark.applicant.application.profile.GetIdentityProfileResult;
import com.spark.applicant.application.profile.GetIdentityProfileUseCase;
import com.spark.applicant.application.profile.IdentityProfileException;
import com.spark.applicant.application.profile.IdentityProfileResult;
import com.spark.applicant.application.profile.UpsertIdentityProfileCommand;
import com.spark.applicant.application.profile.UpsertIdentityProfileUseCase;
import com.spark.applicant.domain.profile.IdentityProfile;
import com.spark.applicant.domain.profile.Nationality;
import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.vesta.lendora.applicant.v1.ApplicantProfileServiceGrpc;
import com.vesta.lendora.applicant.v1.GetIdentityProfileRequest;
import com.vesta.lendora.applicant.v1.GetIdentityProfileResponse;
import com.vesta.lendora.applicant.v1.UpsertIdentityProfileRequest;
import com.vesta.lendora.applicant.v1.UpsertIdentityProfileResponse;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;

@InboundAdapter
public class ApplicantProfileGrpcAdapter implements BindableService {
    private final UpsertIdentityProfileUseCase upsertUseCase;
    private final GetIdentityProfileUseCase getUseCase;
    private final ApplicantProfileServiceGrpc.ApplicantProfileServiceImplBase delegate = new GrpcDelegate();

    @Autowired
    public ApplicantProfileGrpcAdapter(
            UpsertIdentityProfileUseCase upsertUseCase, GetIdentityProfileUseCase getUseCase) {
        this.upsertUseCase = upsertUseCase;
        this.getUseCase = getUseCase;
    }

    @Override
    public ServerServiceDefinition bindService() {
        return delegate.bindService();
    }

    private void upsertIdentityProfile(
            UpsertIdentityProfileRequest request,
            StreamObserver<UpsertIdentityProfileResponse> responseObserver) {
        try {
            IdentityProfileResult result = upsertUseCase.upsert(new UpsertIdentityProfileCommand(
                    request.getApplicantId(),
                    request.getHkidBody(),
                    request.getHkidCheckDigit(),
                    request.getFirstName(),
                    request.getLastName(),
                    request.getChineseName(),
                    toDomainNationality(request.getNationality()),
                    request.getDateOfBirth()));
            responseObserver.onNext(UpsertIdentityProfileResponse.newBuilder()
                    .setProfile(toProto(result.profile()))
                    .build());
            responseObserver.onCompleted();
        } catch (IdentityProfileException error) {
            responseObserver.onError(toStatus(error).withDescription(error.getMessage()).asRuntimeException());
        }
    }

    private void getIdentityProfile(
            GetIdentityProfileRequest request,
            StreamObserver<GetIdentityProfileResponse> responseObserver) {
        GetIdentityProfileResult result = getUseCase.get(new GetIdentityProfileCommand(request.getApplicantId()));
        GetIdentityProfileResponse.Builder response = GetIdentityProfileResponse.newBuilder()
                .setEmpty(result.empty());
        if (!result.empty()) {
            response.setProfile(toProto(result.profile()));
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    private Status toStatus(IdentityProfileException error) {
        return switch (error.getMessage()) {
            case "hkid_invalid", "validation_error", "age_out_of_range" -> Status.INVALID_ARGUMENT;
            default -> Status.UNKNOWN;
        };
    }

    private com.vesta.lendora.applicant.v1.IdentityProfile toProto(IdentityProfile profile) {
        return com.vesta.lendora.applicant.v1.IdentityProfile.newBuilder()
                .setApplicantId(profile.applicantId())
                .setHkidBody(profile.hkidBody())
                .setHkidCheckDigit(profile.hkidCheckDigit())
                .setFirstName(profile.firstName())
                .setLastName(profile.lastName())
                .setChineseName(profile.chineseName())
                .setNationality(toProtoNationality(profile.nationality()))
                .setDateOfBirth(profile.dateOfBirth())
                .build();
    }

    private Nationality toDomainNationality(com.vesta.lendora.applicant.v1.Nationality nationality) {
        return switch (nationality) {
            case NATIONALITY_CHINESE -> Nationality.CHINESE;
            case NATIONALITY_HONG_KONG -> Nationality.HONG_KONG;
            case NATIONALITY_BRITISH -> Nationality.BRITISH;
            case NATIONALITY_INDIAN -> Nationality.INDIAN;
            case NATIONALITY_FILIPINO -> Nationality.FILIPINO;
            case NATIONALITY_INDONESIAN -> Nationality.INDONESIAN;
            case NATIONALITY_PAKISTANI -> Nationality.PAKISTANI;
            case NATIONALITY_AMERICAN -> Nationality.AMERICAN;
            case NATIONALITY_AUSTRALIAN -> Nationality.AUSTRALIAN;
            case NATIONALITY_CANADIAN -> Nationality.CANADIAN;
            case NATIONALITY_OTHER -> Nationality.OTHER;
            default -> throw new IdentityProfileException("validation_error");
        };
    }

    private com.vesta.lendora.applicant.v1.Nationality toProtoNationality(Nationality nationality) {
        return switch (nationality) {
            case CHINESE -> com.vesta.lendora.applicant.v1.Nationality.NATIONALITY_CHINESE;
            case HONG_KONG -> com.vesta.lendora.applicant.v1.Nationality.NATIONALITY_HONG_KONG;
            case BRITISH -> com.vesta.lendora.applicant.v1.Nationality.NATIONALITY_BRITISH;
            case INDIAN -> com.vesta.lendora.applicant.v1.Nationality.NATIONALITY_INDIAN;
            case FILIPINO -> com.vesta.lendora.applicant.v1.Nationality.NATIONALITY_FILIPINO;
            case INDONESIAN -> com.vesta.lendora.applicant.v1.Nationality.NATIONALITY_INDONESIAN;
            case PAKISTANI -> com.vesta.lendora.applicant.v1.Nationality.NATIONALITY_PAKISTANI;
            case AMERICAN -> com.vesta.lendora.applicant.v1.Nationality.NATIONALITY_AMERICAN;
            case AUSTRALIAN -> com.vesta.lendora.applicant.v1.Nationality.NATIONALITY_AUSTRALIAN;
            case CANADIAN -> com.vesta.lendora.applicant.v1.Nationality.NATIONALITY_CANADIAN;
            case OTHER -> com.vesta.lendora.applicant.v1.Nationality.NATIONALITY_OTHER;
        };
    }

    private final class GrpcDelegate extends ApplicantProfileServiceGrpc.ApplicantProfileServiceImplBase {
        @Override
        public void upsertIdentityProfile(
                UpsertIdentityProfileRequest request,
                StreamObserver<UpsertIdentityProfileResponse> responseObserver) {
            ApplicantProfileGrpcAdapter.this.upsertIdentityProfile(request, responseObserver);
        }

        @Override
        public void getIdentityProfile(
                GetIdentityProfileRequest request,
                StreamObserver<GetIdentityProfileResponse> responseObserver) {
            ApplicantProfileGrpcAdapter.this.getIdentityProfile(request, responseObserver);
        }
    }
}
