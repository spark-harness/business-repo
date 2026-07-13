package com.spark.common.spring.cleanarchitecture.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final MethodDescriptor<String, String> TEST_METHOD = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(TEST_SERVICE_NAME, "EchoTrace"))
            .setRequestMarshaller(new StringMarshaller())
            .setResponseMarshaller(new StringMarshaller())
            .build();
    private static final Metadata.Key<String> TRACEPARENT_METADATA_KEY =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
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

    @Test
    void addServices_whenTracingInterceptorConfigured_shouldContinueIncomingGrpcTrace() throws Exception {
        String serverName = "trace-test-" + UUID.randomUUID();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .setSampler(Sampler.alwaysOn())
                        .build())
                .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
                .build();
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(
                List.of(new TraceEchoService()),
                List.of(
                        GrpcTelemetry.builder(openTelemetry).build().createServerInterceptor(),
                        new GrpcServerMetadataInterceptor()),
                0,
                false);
        InProcessServerBuilder builder = InProcessServerBuilder.forName(serverName).directExecutor();
        lifecycle.addServices(builder);
        server = builder.build().start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        Metadata metadata = new Metadata();
        metadata.put(TRACEPARENT_METADATA_KEY, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        AtomicReference<Metadata> responseHeaders = new AtomicReference<>();
        AtomicReference<Metadata> responseTrailers = new AtomicReference<>();

        String traceId = ClientCalls.blockingUnaryCall(
                ClientInterceptors.intercept(
                        channel,
                        io.grpc.stub.MetadataUtils.newCaptureMetadataInterceptor(responseHeaders, responseTrailers),
                        io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(metadata)),
                TEST_METHOD,
                CallOptions.DEFAULT,
                "ping");

        assertThat(traceId).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(responseHeaders.get().get(GrpcServerMetadataInterceptor.TRACE_ID_METADATA_KEY))
                .isEqualTo(traceId);
    }

    @Test
    void addServices_whenGrpcHandlerThrows_shouldEndServerSpanWithError() throws Exception {
        String serverName = "trace-error-test-" + UUID.randomUUID();
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(
                List.of(new ThrowingService()),
                List.of(GrpcTelemetry.builder(openTelemetry).build().createServerInterceptor()),
                0,
                false);
        InProcessServerBuilder builder = InProcessServerBuilder.forName(serverName).directExecutor();
        lifecycle.addServices(builder);
        server = builder.build().start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(channel, TEST_METHOD, CallOptions.DEFAULT, "ping"))
                .isInstanceOf(StatusRuntimeException.class);

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().get(0).getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        provider.close();
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

    private static class TraceEchoService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            ServerCallHandler<String, String> handler = io.grpc.stub.ServerCalls.asyncUnaryCall((request, observer) -> {
                observer.onNext(Span.current().getSpanContext().getTraceId());
                observer.onCompleted();
            });
            return ServerServiceDefinition.builder(TEST_SERVICE_NAME).addMethod(TEST_METHOD, handler).build();
        }
    }

    private static class ThrowingService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            ServerCallHandler<String, String> handler = io.grpc.stub.ServerCalls.asyncUnaryCall((request, observer) -> {
                throw new IllegalStateException("boom");
            });
            return ServerServiceDefinition.builder(TEST_SERVICE_NAME).addMethod(TEST_METHOD, handler).build();
        }
    }

    private static class StringMarshaller implements MethodDescriptor.Marshaller<String> {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new IllegalArgumentException(error);
            }
        }
    }
}
