package com.spark.user.adapter.inbound.grpc;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.user.application.auth.InvalidAuthRequestException;
import com.spark.user.application.auth.RegisterOrLoginCommand;
import com.spark.user.application.auth.RegisterOrLoginResult;
import com.spark.user.application.auth.RegisterOrLoginUseCase;
import com.vesta.spark.user.v1.AuthServiceGrpc;
import com.vesta.spark.user.v1.RegisterOrLoginByMobileCodeRequest;
import com.vesta.spark.user.v1.RegisterOrLoginByMobileCodeResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@InboundAdapter
public class AuthGrpcAdapter extends AuthServiceGrpc.AuthServiceImplBase {
    private final RegisterOrLoginUseCase registerOrLoginUseCase;

    public AuthGrpcAdapter(RegisterOrLoginUseCase registerOrLoginUseCase) {
        this.registerOrLoginUseCase = registerOrLoginUseCase;
    }

    @Override
    public void registerOrLoginByMobileCode(
            RegisterOrLoginByMobileCodeRequest request,
            StreamObserver<RegisterOrLoginByMobileCodeResponse> responseObserver) {
        try {
            RegisterOrLoginResult result = registerOrLoginUseCase.registerOrLogin(
                    new RegisterOrLoginCommand(request.getMobile(), request.getVerificationCode()));
            responseObserver.onNext(RegisterOrLoginByMobileCodeResponse.newBuilder()
                    .setUserId(result.userId())
                    .setNewUser(result.newUser())
                    .build());
            responseObserver.onCompleted();
        } catch (InvalidAuthRequestException error) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription(error.getMessage()).asRuntimeException());
        }
    }
}
