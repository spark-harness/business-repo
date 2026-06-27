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
    private Consul consul = new Consul();

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

    public Consul getConsul() {
        return consul;
    }

    public void setConsul(Consul consul) {
        this.consul = consul;
    }

    public static class Consul {
        private boolean enabled = false;
        private String url = "http://localhost:8500";
        private String serviceName = "origination-api";
        private String serviceAddress = "127.0.0.1";
        private int httpPort = 8080;
        private String healthCheckUrl = "";
        private String healthCheckPath = "/ready";
        private String interval = "10s";
        private String timeout = "2s";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public String getServiceAddress() {
            return serviceAddress;
        }

        public void setServiceAddress(String serviceAddress) {
            this.serviceAddress = serviceAddress;
        }

        public int getHttpPort() {
            return httpPort;
        }

        public void setHttpPort(int httpPort) {
            this.httpPort = httpPort;
        }

        public String getHealthCheckUrl() {
            return healthCheckUrl;
        }

        public void setHealthCheckUrl(String healthCheckUrl) {
            this.healthCheckUrl = healthCheckUrl;
        }

        public String getHealthCheckPath() {
            return healthCheckPath;
        }

        public void setHealthCheckPath(String healthCheckPath) {
            this.healthCheckPath = healthCheckPath;
        }

        public String getInterval() {
            return interval;
        }

        public void setInterval(String interval) {
            this.interval = interval;
        }

        public String getTimeout() {
            return timeout;
        }

        public void setTimeout(String timeout) {
            this.timeout = timeout;
        }
    }
}
