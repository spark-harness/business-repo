package com.spark.origination.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spark.origination.application.CreateLoanApplicationUseCase;
import com.spark.origination.application.GetLoanApplicationUseCase;
import com.spark.origination.application.IdempotencyRepository;
import com.spark.origination.application.LoanApplicationRepository;
import com.spark.origination.application.PatchLoanApplicationUseCase;
import com.spark.origination.application.QuoteGateway;
import com.spark.origination.infrastructure.HttpQuoteGateway;
import com.zaxxer.hikari.HikariDataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OriginationProperties.class)
public class OriginationConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    HttpClient httpClient(OriginationProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getQuoteApiTimeout())
                .build();
    }

    @Bean
    QuoteGateway quoteGateway(HttpClient httpClient, OriginationProperties properties, ObjectMapper objectMapper) {
        return new HttpQuoteGateway(
                httpClient, properties.getQuoteApiBaseUrl(), properties.getQuoteApiTimeout(), objectMapper);
    }

    @Bean
    CreateLoanApplicationUseCase createLoanApplicationUseCase(
            LoanApplicationRepository applications,
            IdempotencyRepository idempotency,
            QuoteGateway quoteGateway,
            Clock clock) {
        return new CreateLoanApplicationUseCase(applications, idempotency, quoteGateway, clock);
    }

    @Bean
    PatchLoanApplicationUseCase patchLoanApplicationUseCase(
            LoanApplicationRepository applications,
            IdempotencyRepository idempotency,
            QuoteGateway quoteGateway,
            Clock clock) {
        return new PatchLoanApplicationUseCase(applications, idempotency, quoteGateway, clock);
    }

    @Bean
    GetLoanApplicationUseCase getLoanApplicationUseCase(LoanApplicationRepository applications) {
        return new GetLoanApplicationUseCase(applications);
    }

    @Bean
    DataSource originationDataSource(OriginationProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getJdbcUrl());
        dataSource.setUsername(properties.getJdbcUsername());
        dataSource.setPassword(properties.getJdbcPassword());
        return dataSource;
    }

    @Bean
    Flyway originationFlyway(DataSource originationDataSource) {
        return Flyway.configure()
                .dataSource(originationDataSource)
                .locations("classpath:db/migration")
                .load();
    }

    @Bean
    FlywayMigrationInitializer originationFlywayMigrationInitializer(Flyway originationFlyway) {
        return new FlywayMigrationInitializer(originationFlyway);
    }
}
