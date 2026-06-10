package com.spark.user.adapter.inbound.grpc;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.user.application.profile.InvalidProfileRequestException;
import com.spark.user.application.profile.SetUserEnabledCommand;
import com.spark.user.application.profile.SetUserEnabledResult;
import com.spark.user.application.profile.SetUserEnabledUseCase;
import com.spark.user.application.profile.UpdateUsernameCommand;
import com.spark.user.application.profile.UpdateUsernameResult;
import com.spark.user.application.profile.UpdateUsernameUseCase;
import com.spark.user.application.profile.UserProfileNotFoundException;
import com.vesta.spark.user.v1.DisableUserRequest;
import com.vesta.spark.user.v1.DisableUserResponse;
import com.vesta.spark.user.v1.ProfileServiceGrpc;
import com.vesta.spark.user.v1.RestoreUserRequest;
import com.vesta.spark.user.v1.RestoreUserResponse;
import com.vesta.spark.user.v1.UpdateUsernameRequest;
import com.vesta.spark.user.v1.UpdateUsernameResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@InboundAdapter
public class ProfileGrpcAdapter extends ProfileServiceGrpc.ProfileServiceImplBase {
    private final UpdateUsernameUseCase updateUsernameUseCase;
    private final SetUserEnabledUseCase setUserEnabledUseCase;

    public ProfileGrpcAdapter(
            UpdateUsernameUseCase updateUsernameUseCase,
            SetUserEnabledUseCase setUserEnabledUseCase) {
        this.updateUsernameUseCase = updateUsernameUseCase;
        this.setUserEnabledUseCase = setUserEnabledUseCase;
    }

    @Override
    public void updateUsername(
            UpdateUsernameRequest request,
            StreamObserver<UpdateUsernameResponse> responseObserver) {
        try {
            UpdateUsernameResult result = updateUsernameUseCase.updateUsername(
                    new UpdateUsernameCommand(request.getUserId(), request.getUsername()));
            responseObserver.onNext(UpdateUsernameResponse.newBuilder()
                    .setUserId(result.userId())
                    .setUsername(result.username())
                    .build());
            responseObserver.onCompleted();
        } catch (InvalidProfileRequestException error) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(error.getMessage()).asRuntimeException());
        } catch (UserProfileNotFoundException error) {
            responseObserver.onError(
                    Status.NOT_FOUND.withDescription(error.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void disableUser(
            DisableUserRequest request,
            StreamObserver<DisableUserResponse> responseObserver) {
        try {
            SetUserEnabledResult result = setUserEnabledUseCase.setEnabled(
                    new SetUserEnabledCommand(request.getUserId(), false));
            responseObserver.onNext(DisableUserResponse.newBuilder()
                    .setUserId(result.userId())
                    .setEnabled(result.enabled())
                    .build());
            responseObserver.onCompleted();
        } catch (InvalidProfileRequestException error) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(error.getMessage()).asRuntimeException());
        } catch (UserProfileNotFoundException error) {
            responseObserver.onError(
                    Status.NOT_FOUND.withDescription(error.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void restoreUser(
            RestoreUserRequest request,
            StreamObserver<RestoreUserResponse> responseObserver) {
        try {
            SetUserEnabledResult result = setUserEnabledUseCase.setEnabled(
                    new SetUserEnabledCommand(request.getUserId(), true));
            responseObserver.onNext(RestoreUserResponse.newBuilder()
                    .setUserId(result.userId())
                    .setEnabled(result.enabled())
                    .build());
            responseObserver.onCompleted();
        } catch (InvalidProfileRequestException error) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(error.getMessage()).asRuntimeException());
        } catch (UserProfileNotFoundException error) {
            responseObserver.onError(
                    Status.NOT_FOUND.withDescription(error.getMessage()).asRuntimeException());
        }
    }
}
