package com.spark.applicant.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.applicant.application.profile.GetIdentityProfileUseCase;
import com.spark.applicant.application.profile.UpsertIdentityProfileUseCase;
import com.spark.applicant.infrastructure.profile.InMemoryIdentityProfileRepository;
import com.vesta.lendora.applicant.v1.ApplicantProfileServiceGrpc;
import com.vesta.lendora.applicant.v1.GetIdentityProfileRequest;
import com.vesta.lendora.applicant.v1.GetIdentityProfileResponse;
import com.vesta.lendora.applicant.v1.Nationality;
import com.vesta.lendora.applicant.v1.UpsertIdentityProfileRequest;
import com.vesta.lendora.applicant.v1.UpsertIdentityProfileResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApplicantProfileGrpcAdapterTest {
    private io.grpc.Server server;
    private ManagedChannel channel;
    private ApplicantProfileServiceGrpc.ApplicantProfileServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "applicant-profile-test-" + UUID.randomUUID();
        InMemoryIdentityProfileRepository repository = new InMemoryIdentityProfileRepository();
        Clock clock = Clock.fixed(Instant.parse("2026-06-28T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"));
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new ApplicantProfileGrpcAdapter(
                        new UpsertIdentityProfileUseCase(repository, clock),
                        new GetIdentityProfileUseCase(repository)))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        stub = ApplicantProfileServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void upsertIdentityProfile_whenRequestIsValid_shouldSaveAndReturnProfile() {
        UpsertIdentityProfileResponse response = stub.upsertIdentityProfile(validRequest().build());

        assertThat(response.getProfile().getApplicantId()).isEqualTo("applicant_001");
        assertThat(response.getProfile().getHkidBody()).isEqualTo("A123456");
        assertThat(response.getProfile().getNationality()).isEqualTo(Nationality.NATIONALITY_HONG_KONG);
    }

    @Test
    void getIdentityProfile_whenProfileExists_shouldReturnSavedProfile() {
        stub.upsertIdentityProfile(validRequest().build());

        GetIdentityProfileResponse response = stub.getIdentityProfile(GetIdentityProfileRequest.newBuilder()
                .setApplicantId("applicant_001")
                .build());

        assertThat(response.getEmpty()).isFalse();
        assertThat(response.getProfile().getFirstName()).isEqualTo("Ada");
    }

    @Test
    void getIdentityProfile_whenProfileDoesNotExist_shouldReturnEmpty() {
        GetIdentityProfileResponse response = stub.getIdentityProfile(GetIdentityProfileRequest.newBuilder()
                .setApplicantId("applicant_001")
                .build());

        assertThat(response.getEmpty()).isTrue();
        assertThat(response.hasProfile()).isFalse();
    }

    @Test
    void upsertIdentityProfile_whenHkidIsInvalid_shouldReturnInvalidArgument() {
        UpsertIdentityProfileRequest request = validRequest().setHkidCheckDigit("4").build();

        assertThatThrownBy(() -> stub.upsertIdentityProfile(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private UpsertIdentityProfileRequest.Builder validRequest() {
        return UpsertIdentityProfileRequest.newBuilder()
                .setApplicantId("applicant_001")
                .setHkidBody("A123456")
                .setHkidCheckDigit("3")
                .setFirstName("Ada")
                .setLastName("Lovelace")
                .setChineseName("Test Name")
                .setNationality(Nationality.NATIONALITY_HONG_KONG)
                .setDateOfBirth("1990-01-15");
    }
}
