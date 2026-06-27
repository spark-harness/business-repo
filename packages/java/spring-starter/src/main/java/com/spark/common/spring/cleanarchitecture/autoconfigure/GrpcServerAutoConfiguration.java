package com.spark.common.spring.cleanarchitecture.autoconfigure;

import com.spark.common.spring.cleanarchitecture.grpc.GrpcServerLifecycle;
import com.spark.common.spring.security.RequestPrincipalGrpcServerInterceptor;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

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
}
