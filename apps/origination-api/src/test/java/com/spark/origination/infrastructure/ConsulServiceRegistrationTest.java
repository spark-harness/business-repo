package com.spark.origination.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.origination.bootstrap.OriginationProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ConsulServiceRegistrationTest {
    @Test
    void requestBody_whenBuilt_shouldUseServiceDnsAndReadyCheck() {
        OriginationProperties properties = new OriginationProperties();
        properties.getConsul().setServiceName("origination-api");
        properties.getConsul().setServiceAddress("origination-api.lendora-sta-origination-api.svc.cluster.local");
        properties.getConsul().setHttpPort(80);
        properties.getConsul().setGrpcPort(9091);
        properties.getConsul().setHealthCheckUrl(
                "http://origination-api.lendora-sta-origination-api.svc.cluster.local:80/ready");

        ConsulServiceRegistration registration = new ConsulServiceRegistration(
                properties, URI.create("http://consul.lendora-sta-consul.svc.cluster.local:8500"));

        ConsulServiceRegistration.ServiceRegistrationRequest request = registration.buildRequest();

        assertThat(request.name()).isEqualTo("origination-api");
        assertThat(request.address()).isEqualTo("origination-api.lendora-sta-origination-api.svc.cluster.local");
        assertThat(request.port()).isEqualTo(80);
        assertThat(request.meta()).containsEntry("grpc_port", "9091");
        assertThat(request.check().http()).isEqualTo(
                "http://origination-api.lendora-sta-origination-api.svc.cluster.local:80/ready");
    }

    @Test
    void requestBody_whenHealthCheckUrlMissing_shouldBuildFromServiceAddress() {
        OriginationProperties properties = new OriginationProperties();
        properties.getConsul().setServiceName("origination-api");
        properties.getConsul().setServiceAddress("origination-api.lendora-sta-origination-api.svc.cluster.local");
        properties.getConsul().setHttpPort(80);
        properties.getConsul().setHealthCheckPath("/ready");

        ConsulServiceRegistration registration = new ConsulServiceRegistration(
                properties, URI.create("http://consul.lendora-sta-consul.svc.cluster.local:8500"));

        ConsulServiceRegistration.ServiceRegistrationRequest request = registration.buildRequest();

        assertThat(request.check().http()).isEqualTo(
                "http://origination-api.lendora-sta-origination-api.svc.cluster.local:80/ready");
    }

    @Test
    void check_whenStartupRegistrationFails_shouldRemainNotReady() {
        OriginationProperties properties = new OriginationProperties();
        ConsulServiceRegistration registration =
                new ConsulServiceRegistration(properties, URI.create("http://127.0.0.1:1"));

        assertThat(registration.check().up()).isFalse();
    }
}
