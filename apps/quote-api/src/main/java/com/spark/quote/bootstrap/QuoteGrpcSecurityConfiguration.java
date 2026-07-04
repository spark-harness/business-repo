package com.spark.quote.bootstrap;

import com.spark.common.spring.security.RequestPrincipalGrpcServerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class QuoteGrpcSecurityConfiguration {
    @Bean
    RequestPrincipalGrpcServerInterceptor requestPrincipalGrpcServerInterceptor() {
        return new RequestPrincipalGrpcServerInterceptor("QUOTE-AUTH-0001");
    }
}
