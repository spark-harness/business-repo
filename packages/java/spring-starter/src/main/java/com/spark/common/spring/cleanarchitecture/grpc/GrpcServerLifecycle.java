package com.spark.common.spring.cleanarchitecture.grpc;

import io.grpc.BindableService;
import io.grpc.ServerInterceptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;
import java.util.List;
import org.springframework.context.SmartLifecycle;

public class GrpcServerLifecycle implements SmartLifecycle {
    private final List<BindableService> services;
    private final List<ServerInterceptor> interceptors;
    private final int port;
    private final boolean reflectionEnabled;
    private Server server;
    private boolean running;

    public GrpcServerLifecycle(List<BindableService> services, int port, boolean reflectionEnabled) {
        this(services, List.of(), port, reflectionEnabled);
    }

    public GrpcServerLifecycle(
            List<BindableService> services,
            List<ServerInterceptor> interceptors,
            int port,
            boolean reflectionEnabled) {
        this.services = List.copyOf(services);
        this.interceptors = List.copyOf(interceptors);
        this.port = port;
        this.reflectionEnabled = reflectionEnabled;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }

        NettyServerBuilder builder = NettyServerBuilder.forPort(port);
        addServices(builder);
        try {
            server = builder.build().start();
            running = true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gRPC server on port " + port, e);
        }
    }

    void addServices(ServerBuilder<?> builder) {
        services.forEach(service -> {
            if (interceptors.isEmpty()) {
                builder.addService(service);
                return;
            }
            builder.addService(ServerInterceptors.intercept(service, interceptors));
        });
        if (reflectionEnabled) {
            builder.addService(ProtoReflectionServiceV1.newInstance());
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
        running = false;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
