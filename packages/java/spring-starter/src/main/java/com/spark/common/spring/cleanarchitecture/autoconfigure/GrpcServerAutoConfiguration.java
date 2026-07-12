package com.spark.common.spring.cleanarchitecture.autoconfigure;

import com.spark.common.spring.cleanarchitecture.grpc.GrpcServerLifecycle;
import com.spark.common.spring.cleanarchitecture.grpc.GrpcServerMetadataInterceptor;
import com.spark.common.spring.security.RequestPrincipalGrpcServerInterceptor;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@AutoConfiguration
@ConditionalOnClass({Server.class, NettyServerBuilder.class, ProtoReflectionServiceV1.class})
@ConditionalOnBean(BindableService.class)
@ConditionalOnProperty(prefix = "spark.grpc.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GrpcServerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(GrpcServerLifecycle.class)
    GrpcServerLifecycle grpcServerLifecycle(
            List<BindableService> services,
            List<ServerInterceptor> interceptors,
            @Value("${spark.grpc.server.port:9090}") int port,
            @Value("${spark.grpc.server.reflection.enabled:true}") boolean reflectionEnabled) {
        return new GrpcServerLifecycle(services, interceptors, port, reflectionEnabled);
    }

    @Bean
    @ConditionalOnMissingBean(RequestPrincipalGrpcServerInterceptor.class)
    @ConditionalOnProperty(
            prefix = "spark.grpc.server.principal",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    RequestPrincipalGrpcServerInterceptor requestPrincipalGrpcServerInterceptor() {
        return new RequestPrincipalGrpcServerInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean(name = "openTelemetryGrpcServerInterceptor")
    @ConditionalOnProperty(
            prefix = "spark.grpc.server.tracing",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ServerInterceptor openTelemetryGrpcServerInterceptor(ObjectProvider<OpenTelemetry> openTelemetry) {
        return GrpcTelemetry.builder(openTelemetry.getIfAvailable(GlobalOpenTelemetry::get))
                .build()
                .createServerInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean(GrpcServerMetadataInterceptor.class)
    @ConditionalOnProperty(
            prefix = "spark.grpc.server.tracing",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @Order(Ordered.HIGHEST_PRECEDENCE + 50)
    GrpcServerMetadataInterceptor grpcServerMetadataInterceptor() {
        return new GrpcServerMetadataInterceptor();
    }
}
