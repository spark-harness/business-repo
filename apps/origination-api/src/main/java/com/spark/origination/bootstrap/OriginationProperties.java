package com.spark.origination.bootstrap;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spark.origination")
public class OriginationProperties {
    private String jdbcUrl = "jdbc:h2:mem:origination;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private String jdbcUsername = "sa";
    private String jdbcPassword = "";
    private String quoteApiBaseUrl = "http://localhost:8080";
    private Duration quoteApiTimeout = Duration.ofSeconds(3);

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getJdbcUsername() {
        return jdbcUsername;
    }

    public void setJdbcUsername(String jdbcUsername) {
        this.jdbcUsername = jdbcUsername;
    }

    public String getJdbcPassword() {
        return jdbcPassword;
    }

    public void setJdbcPassword(String jdbcPassword) {
        this.jdbcPassword = jdbcPassword;
    }

    public String getQuoteApiBaseUrl() {
        return quoteApiBaseUrl;
    }

    public void setQuoteApiBaseUrl(String quoteApiBaseUrl) {
        this.quoteApiBaseUrl = quoteApiBaseUrl;
    }

    public Duration getQuoteApiTimeout() {
        return quoteApiTimeout;
    }

    public void setQuoteApiTimeout(Duration quoteApiTimeout) {
        this.quoteApiTimeout = quoteApiTimeout;
    }
}
