package com.spark.origination.bootstrap;

import com.spark.common.spring.security.RequestPrincipalGrpcServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OriginationGrpcSecurityConfiguration {
    @Bean
    RequestPrincipalGrpcServerInterceptor requestPrincipalGrpcServerInterceptor() {
        return new RequestPrincipalGrpcServerInterceptor("ORIGINATION-AUTH-0001");
    }
}
