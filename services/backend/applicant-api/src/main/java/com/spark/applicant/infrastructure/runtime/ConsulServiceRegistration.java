package com.spark.applicant.infrastructure.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spark.applicant.application.runtime.RuntimeDependencyProbe;
import com.spark.applicant.bootstrap.ApplicantAuthProperties;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;

@InfrastructureAdapter
@ConditionalOnProperty(prefix = "spark.applicant.auth.consul", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConsulServiceRegistration implements ApplicationRunner, RuntimeDependencyProbe {
    private final ApplicantAuthProperties properties;
    private final URI consulUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile boolean registered;

    @Autowired
    public ConsulServiceRegistration(ApplicantAuthProperties properties, ObjectMapper objectMapper) {
        this(properties, URI.create(properties.getConsul().getUrl()), HttpClient.newHttpClient(), objectMapper);
    }

    ConsulServiceRegistration(ApplicantAuthProperties properties, URI consulUrl) {
        this(properties, consulUrl, HttpClient.newHttpClient(), new ObjectMapper());
    }

    private ConsulServiceRegistration(
            ApplicantAuthProperties properties, URI consulUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.consulUrl = consulUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            HttpRequest request = HttpRequest.newBuilder(consulUrl.resolve("/v1/agent/service/register"))
                    .PUT(HttpRequest.BodyPublishers.ofString(toJson(buildRequest())))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            registered = response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException error) {
            registered = false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            registered = false;
        }
    }

    @Override
    public Status check() {
        return registered ? Status.up("consul") : Status.down("consul");
    }

    ServiceRegistrationRequest buildRequest() {
        ApplicantAuthProperties.Consul consul = properties.getConsul();
        return new ServiceRegistrationRequest(
                consul.getServiceName(),
                consul.getServiceAddress(),
                consul.getHttpPort(),
                Map.of("grpc_port", Integer.toString(consul.getGrpcPort())),
                new HealthCheck(healthCheckUrl(consul), consul.getInterval(), consul.getTimeout()));
    }

    private String healthCheckUrl(ApplicantAuthProperties.Consul consul) {
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
