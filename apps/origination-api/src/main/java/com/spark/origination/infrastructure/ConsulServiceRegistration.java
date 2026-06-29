package com.spark.origination.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import com.spark.origination.application.runtime.RuntimeDependencyProbe;
import com.spark.origination.bootstrap.OriginationProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@InfrastructureAdapter
@ConditionalOnProperty(prefix = "spark.origination.consul", name = "enabled", havingValue = "true")
public class ConsulServiceRegistration implements ApplicationRunner, RuntimeDependencyProbe {
    private final OriginationProperties properties;
    private final URI consulUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile boolean registered;

    @Autowired
    public ConsulServiceRegistration(OriginationProperties properties, ObjectMapper objectMapper) {
        this(properties, URI.create(properties.getConsul().getUrl()), HttpClient.newHttpClient(), objectMapper);
    }

    ConsulServiceRegistration(OriginationProperties properties, URI consulUrl) {
        this(properties, consulUrl, HttpClient.newHttpClient(), new ObjectMapper());
    }

    private ConsulServiceRegistration(
            OriginationProperties properties, URI consulUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.consulUrl = consulUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        registered = register();
    }

    @Override
    public Status check() {
        if (!registered) {
            registered = register();
        }
        return registered ? Status.up("consul") : Status.down("consul");
    }

    private boolean register() {
        try {
            HttpRequest request = HttpRequest.newBuilder(consulUrl.resolve("/v1/agent/service/register"))
                    .PUT(HttpRequest.BodyPublishers.ofString(toJson(buildRequest())))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException error) {
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    ServiceRegistrationRequest buildRequest() {
        OriginationProperties.Consul consul = properties.getConsul();
        return new ServiceRegistrationRequest(
                consul.getServiceName(),
                consul.getServiceAddress(),
                consul.getHttpPort(),
                Map.of("grpc_port", Integer.toString(consul.getGrpcPort())),
                new HealthCheck(healthCheckUrl(consul), consul.getInterval(), consul.getTimeout()));
    }

    private String healthCheckUrl(OriginationProperties.Consul consul) {
        if (consul.getHealthCheckUrl() != null && !consul.getHealthCheckUrl().isBlank()) {
            return consul.getHealthCheckUrl();
        }
        return "http://%s:%d%s".formatted(consul.getServiceAddress(), consul.getHttpPort(), consul.getHealthCheckPath());
    }

    private String toJson(ServiceRegistrationRequest request) throws JsonProcessingException {
        return objectMapper.writeValueAsString(request);
    }

    record ServiceRegistrationRequest(
            @JsonProperty("Name") String name,
            @JsonProperty("Address") String address,
            @JsonProperty("Port") int port,
            @JsonProperty("Meta") Map<String, String> meta,
            @JsonProperty("Check") HealthCheck check) {}

    record HealthCheck(
            @JsonProperty("HTTP") String http,
            @JsonProperty("Interval") String interval,
            @JsonProperty("Timeout") String timeout) {}
}
