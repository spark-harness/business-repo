package com.spark.user.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.user.application.ping.PingUseCase;
import com.vesta.spark.user.v1.PingRequest;
import com.vesta.spark.user.v1.PingResponse;
import com.vesta.spark.user.v1.PingServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PingGrpcAdapterTest {
    private io.grpc.Server server;
    private ManagedChannel channel;
    private PingServiceGrpc.PingServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "ping-test-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new PingGrpcAdapter(new PingUseCase()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
        stub = PingServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void ping_whenNameIsForest_shouldReturnPongMessage() {
        PingResponse response = stub.ping(PingRequest.newBuilder().setName("forest").build());

        assertThat(response.getMessage()).isEqualTo("pong, forest");
    }

    @Test
    void ping_whenNameIsAlice_shouldReturnPongMessage() {
        PingResponse response = stub.ping(PingRequest.newBuilder().setName("Alice").build());

        assertThat(response.getMessage()).isEqualTo("pong, Alice");
    }

    @Test
    void ping_whenNameIsBlank_shouldReturnInvalidArgument() {
        PingRequest request = PingRequest.newBuilder().setName("   ").build();

        assertThatThrownBy(() -> stub.ping(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void ping_whenNameIsBlank_shouldNotReturnPongMessage() {
        CapturingResponseObserver observer = new CapturingResponseObserver();

        new PingGrpcAdapter(new PingUseCase()).ping(PingRequest.getDefaultInstance(), observer);

        assertThat(observer.response).isNull();
        assertThat(observer.error).isInstanceOf(StatusRuntimeException.class);
    }

    private static class CapturingResponseObserver implements StreamObserver<PingResponse> {
        private PingResponse response;
        private Throwable error;

        @Override
        public void onNext(PingResponse value) {
            response = value;
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onCompleted() {}
    }
}
