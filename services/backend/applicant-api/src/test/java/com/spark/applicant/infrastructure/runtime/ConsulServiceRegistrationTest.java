package com.spark.applicant.infrastructure.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.applicant.bootstrap.ApplicantAuthProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ConsulServiceRegistrationTest {
    @Test
    void requestBody_whenBuilt_shouldContainServiceNamePortsAndHealthCheck() {
        ApplicantAuthProperties properties = new ApplicantAuthProperties();
        properties.getConsul().setServiceName("applicant-api");
        properties.getConsul().setServiceAddress("127.0.0.1");
        properties.getConsul().setHttpPort(8080);
        properties.getConsul().setGrpcPort(9090);
        properties.getConsul().setHealthCheckPath("/ready");

        ConsulServiceRegistration registration = new ConsulServiceRegistration(properties, URI.create("http://localhost:8500"));

        ConsulServiceRegistration.ServiceRegistrationRequest request = registration.buildRequest();

        assertThat(request.name()).isEqualTo("applicant-api");
        assertThat(request.address()).isEqualTo("127.0.0.1");
        assertThat(request.port()).isEqualTo(8080);
        assertThat(request.meta()).containsEntry("grpc_port", "9090");
        assertThat(request.check().http()).isEqualTo("http://127.0.0.1:8080/ready");
    }

    @Test
    void requestBody_whenHealthCheckUrlIsConfigured_shouldKeepServiceAddressForDiscovery() {
        ApplicantAuthProperties properties = new ApplicantAuthProperties();
        properties.getConsul().setServiceName("applicant-api");
        properties.getConsul().setServiceAddress("127.0.0.1");
        properties.getConsul().setHttpPort(8080);
        properties.getConsul().setHealthCheckUrl("http://host.docker.internal:8080/ready");

        ConsulServiceRegistration registration = new ConsulServiceRegistration(properties, URI.create("http://localhost:8500"));

        ConsulServiceRegistration.ServiceRegistrationRequest request = registration.buildRequest();

        assertThat(request.address()).isEqualTo("127.0.0.1");
        assertThat(request.check().http()).isEqualTo("http://host.docker.internal:8080/ready");
    }
}
