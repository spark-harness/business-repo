package com.spark.quote.bootstrap;

import com.spark.quote.application.CreateQuoteUseCase;
import com.spark.quote.application.GetQuoteUseCase;
import com.spark.quote.application.QuoteRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QuoteProperties.class)
public class QuoteConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CreateQuoteUseCase createQuoteUseCase(QuoteRepository repository, Clock clock, QuoteProperties properties) {
        return new CreateQuoteUseCase(repository, clock, properties.getValidity());
    }

    @Bean
    GetQuoteUseCase getQuoteUseCase(QuoteRepository repository, Clock clock) {
        return new GetQuoteUseCase(repository, clock);
    }

    @Bean
    DataSource quoteDataSource(QuoteProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getJdbcUrl());
        dataSource.setUsername(properties.getJdbcUsername());
        dataSource.setPassword(properties.getJdbcPassword());
        return dataSource;
    }

    @Bean
    Flyway quoteFlyway(DataSource quoteDataSource) {
        return Flyway.configure()
                .dataSource(quoteDataSource)
                .locations("classpath:db/migration")
                .load();
    }

    @Bean
    FlywayMigrationInitializer quoteFlywayMigrationInitializer(Flyway quoteFlyway) {
        return new FlywayMigrationInitializer(quoteFlyway);
    }
}
