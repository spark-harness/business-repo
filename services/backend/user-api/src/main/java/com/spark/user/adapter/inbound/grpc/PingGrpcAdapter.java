package com.spark.user.adapter.inbound.grpc;

import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.spark.user.application.ping.PingCommand;
import com.spark.user.application.ping.PingResult;
import com.spark.user.application.ping.PingUseCase;
import com.vesta.spark.user.v1.PingRequest;
import com.vesta.spark.user.v1.PingResponse;
import com.vesta.spark.user.v1.PingServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@InboundAdapter
public class PingGrpcAdapter extends PingServiceGrpc.PingServiceImplBase {
    private final PingUseCase pingUseCase;

    public PingGrpcAdapter(PingUseCase pingUseCase) {
        this.pingUseCase = pingUseCase;
    }

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        String name = request.getName().trim();
        if (name.isEmpty()) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription("name must not be blank").asRuntimeException());
            return;
        }

        PingResult result = pingUseCase.ping(new PingCommand(name));
        responseObserver.onNext(PingResponse.newBuilder().setMessage(result.message()).build());
        responseObserver.onCompleted();
    }
}
