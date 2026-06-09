package com.spark.user.adapter.inbound.grpc;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.user.application.profile.InvalidProfileRequestException;
import com.spark.user.application.profile.UpdateUsernameCommand;
import com.spark.user.application.profile.UpdateUsernameResult;
import com.spark.user.application.profile.UpdateUsernameUseCase;
import com.spark.user.application.profile.UserProfileNotFoundException;
import com.vesta.spark.user.v1.ProfileServiceGrpc;
import com.vesta.spark.user.v1.UpdateUsernameRequest;
import com.vesta.spark.user.v1.UpdateUsernameResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@InboundAdapter
public class ProfileGrpcAdapter extends ProfileServiceGrpc.ProfileServiceImplBase {
    private final UpdateUsernameUseCase updateUsernameUseCase;

    public ProfileGrpcAdapter(UpdateUsernameUseCase updateUsernameUseCase) {
        this.updateUsernameUseCase = updateUsernameUseCase;
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
}
