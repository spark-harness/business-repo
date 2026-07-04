package com.spark.quote.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.quote.bootstrap.QuoteProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ConsulServiceRegistrationTest {
    @Test
    void requestBody_whenBuilt_shouldUseServiceDnsAndReadyCheck() {
        QuoteProperties properties = new QuoteProperties();
        properties.getConsul().setServiceName("quote-api");
        properties.getConsul().setServiceAddress("quote-api.lendora-sta-quote-api.svc.cluster.local");
        properties.getConsul().setHttpPort(80);
        properties.getConsul().setGrpcPort(9090);
        properties.getConsul().setHealthCheckUrl(
                "http://quote-api.lendora-sta-quote-api.svc.cluster.local:80/ready");

        ConsulServiceRegistration registration = new ConsulServiceRegistration(
                properties, URI.create("http://consul.lendora-sta-consul.svc.cluster.local:8500"));

        ConsulServiceRegistration.ServiceRegistrationRequest request = registration.buildRequest();

        assertThat(request.name()).isEqualTo("quote-api");
        assertThat(request.address()).isEqualTo("quote-api.lendora-sta-quote-api.svc.cluster.local");
        assertThat(request.port()).isEqualTo(80);
        assertThat(request.meta()).containsEntry("grpc_port", "9090");
        assertThat(request.check().http()).isEqualTo(
                "http://quote-api.lendora-sta-quote-api.svc.cluster.local:80/ready");
    }

    @Test
    void requestBody_whenHealthCheckUrlMissing_shouldBuildFromServiceAddress() {
        QuoteProperties properties = new QuoteProperties();
        properties.getConsul().setServiceName("quote-api");
        properties.getConsul().setServiceAddress("quote-api.lendora-sta-quote-api.svc.cluster.local");
        properties.getConsul().setHttpPort(80);
        properties.getConsul().setHealthCheckPath("/ready");

        ConsulServiceRegistration registration = new ConsulServiceRegistration(
                properties, URI.create("http://consul.lendora-sta-consul.svc.cluster.local:8500"));

        ConsulServiceRegistration.ServiceRegistrationRequest request = registration.buildRequest();

        assertThat(request.check().http()).isEqualTo(
                "http://quote-api.lendora-sta-quote-api.svc.cluster.local:80/ready");
    }

    @Test
    void check_whenStartupRegistrationFails_shouldRemainNotReady() {
        QuoteProperties properties = new QuoteProperties();
        ConsulServiceRegistration registration =
                new ConsulServiceRegistration(properties, URI.create("http://127.0.0.1:1"));

        assertThat(registration.check().up()).isFalse();
    }
}
