package com.spark.common.spring.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(FilterRegistrationBean.class)
public class RequestPrincipalHttpAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(RequestPrincipalHttpFilter.class)
    RequestPrincipalHttpFilter requestPrincipalHttpFilter() {
        return new RequestPrincipalHttpFilter();
    }

    @Bean
    @ConditionalOnMissingBean(name = "requestPrincipalHttpFilterRegistration")
    FilterRegistrationBean<RequestPrincipalHttpFilter> requestPrincipalHttpFilterRegistration(
            RequestPrincipalHttpFilter filter) {
        FilterRegistrationBean<RequestPrincipalHttpFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("requestPrincipalHttpFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(-100);
        return registration;
    }
}
