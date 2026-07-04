package com.spark.origination.bootstrap;

import com.spark.origination.application.AdvanceApplicationStepUseCase;
import com.spark.origination.application.CreateLoanApplicationUseCase;
import com.spark.origination.application.GetLoanApplicationUseCase;
import com.spark.origination.application.IdempotencyRepository;
import com.spark.origination.application.LoanApplicationRepository;
import com.spark.origination.application.PatchLoanApplicationUseCase;
import com.spark.origination.application.QuoteGateway;
import com.spark.origination.infrastructure.GrpcQuoteGateway;
import com.vesta.lendora.quote.v1.QuoteServiceGrpc;
import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import javax.sql.DataSource;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

    @Bean(destroyMethod = "shutdown")
    ManagedChannel quoteApiChannel(OriginationProperties properties) {
        return ManagedChannelBuilder.forTarget(properties.getQuoteApiGrpcTarget())
                .usePlaintext()
                .build();
    }

    @Bean
    QuoteServiceGrpc.QuoteServiceBlockingStub quoteServiceBlockingStub(ManagedChannel quoteApiChannel) {
        return QuoteServiceGrpc.newBlockingStub(quoteApiChannel);
    }

    @Bean
    @ConditionalOnMissingBean(QuoteGateway.class)
    QuoteGateway quoteGateway(
            QuoteServiceGrpc.QuoteServiceBlockingStub quoteStub,
            OriginationProperties properties,
            OpenTelemetry openTelemetry) {
        return new GrpcQuoteGateway(quoteStub, properties.getQuoteApiGrpcTimeout(), openTelemetry);
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
    AdvanceApplicationStepUseCase advanceApplicationStepUseCase(
            LoanApplicationRepository applications, Clock clock) {
        return new AdvanceApplicationStepUseCase(applications, clock);
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
