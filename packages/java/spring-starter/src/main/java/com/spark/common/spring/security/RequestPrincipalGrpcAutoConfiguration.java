package com.spark.common.spring.security;

import io.grpc.ClientInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(ClientInterceptor.class)
public class RequestPrincipalGrpcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(RequestPrincipalGrpcClientInterceptor.class)
    RequestPrincipalGrpcClientInterceptor requestPrincipalGrpcClientInterceptor() {
        return new RequestPrincipalGrpcClientInterceptor();
    }
}
