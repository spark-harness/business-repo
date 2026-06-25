package com.spark.common.spring.cleanarchitecture.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrpcServerLifecycleTest {
    private static final String TEST_SERVICE_NAME = "spark.test.TestService";
    private Server server;
    private ManagedChannel channel;

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
    void addServices_whenReflectionEnabled_shouldExposeServerReflectionService() throws Exception {
        startInProcessServer(true);

        assertThat(listServiceNames()).contains(ServerReflectionGrpc.SERVICE_NAME);
    }

    private void startInProcessServer(boolean reflectionEnabled) throws IOException {
        String serverName = "reflection-test-" + UUID.randomUUID();
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(List.of(new TestBindableService()), 0, reflectionEnabled);
        InProcessServerBuilder builder = InProcessServerBuilder.forName(serverName).directExecutor();
        lifecycle.addServices(builder);
        server = builder.build().start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    }

    private List<String> listServiceNames() throws InterruptedException {
        List<String> serviceNames = new ArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        ServerReflectionGrpc.ServerReflectionStub stub = ServerReflectionGrpc.newStub(channel);
        StreamObserver<ServerReflectionRequest> requests =
                stub.serverReflectionInfo(new StreamObserver<ServerReflectionResponse>() {
                    @Override
                    public void onNext(ServerReflectionResponse response) {
                        response.getListServicesResponse()
                                .getServiceList()
                                .forEach(service -> serviceNames.add(service.getName()));
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        error.set(throwable);
                        completed.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        completed.countDown();
                    }
                });

        requests.onNext(ServerReflectionRequest.newBuilder().setListServices("").build());
        requests.onCompleted();

        assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        return serviceNames;
    }

    private static class TestBindableService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return ServerServiceDefinition.builder(TEST_SERVICE_NAME).build();
        }
    }
}
