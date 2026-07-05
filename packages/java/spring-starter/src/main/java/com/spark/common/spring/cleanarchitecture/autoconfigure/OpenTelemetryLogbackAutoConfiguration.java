package com.spark.common.spring.cleanarchitecture.autoconfigure;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(OpenTelemetryAppender.class)
public class OpenTelemetryLogbackAutoConfiguration {
    @Bean
    ApplicationListener<ApplicationReadyEvent> sparkOpenTelemetryLogbackAppenderInstaller(
            ObjectProvider<OpenTelemetry> openTelemetry) {
        return event -> OpenTelemetryAppender.install(openTelemetry.getIfAvailable(GlobalOpenTelemetry::get));
    }
}
