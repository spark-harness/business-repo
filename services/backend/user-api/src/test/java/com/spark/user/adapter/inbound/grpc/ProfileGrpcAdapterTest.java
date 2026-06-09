package com.spark.user.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.user.application.auth.RegisterOrLoginCommand;
import com.spark.user.application.auth.RegisterOrLoginResult;
import com.spark.user.application.auth.RegisterOrLoginUseCase;
import com.spark.user.application.profile.UpdateUsernameUseCase;
import com.spark.user.infrastructure.auth.FixedVerificationCodeVerifier;
import com.spark.user.infrastructure.auth.InMemoryUserRepository;
import com.vesta.spark.user.v1.ProfileServiceGrpc;
import com.vesta.spark.user.v1.UpdateUsernameRequest;
import com.vesta.spark.user.v1.UpdateUsernameResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileGrpcAdapterTest {
    private RegisterOrLoginUseCase registerOrLoginUseCase;
    private io.grpc.Server server;
    private ManagedChannel channel;
    private ProfileServiceGrpc.ProfileServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "profile-test-" + UUID.randomUUID();
        InMemoryUserRepository userRepository = new InMemoryUserRepository();
        registerOrLoginUseCase =
                new RegisterOrLoginUseCase(userRepository, new FixedVerificationCodeVerifier());
        UpdateUsernameUseCase updateUsernameUseCase = new UpdateUsernameUseCase(userRepository);
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new ProfileGrpcAdapter(updateUsernameUseCase))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
        stub = ProfileServiceGrpc.newBlockingStub(channel);
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
    void updateUsername_whenUserExists_shouldReturnUpdatedUsername() {
        RegisterOrLoginResult user = registerOrLoginUseCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));

        UpdateUsernameResponse response = stub.updateUsername(UpdateUsernameRequest.newBuilder()
                .setUserId(user.userId())
                .setUsername(" Alice ")
                .build());

        assertThat(response.getUserId()).isEqualTo(user.userId());
        assertThat(response.getUsername()).isEqualTo("Alice");
    }

    @Test
    void updateUsername_whenUsernameIsBlank_shouldReturnInvalidArgument() {
        RegisterOrLoginResult user = registerOrLoginUseCase.registerOrLogin(
                new RegisterOrLoginCommand("13800138000", "123456"));
        UpdateUsernameRequest request = UpdateUsernameRequest.newBuilder()
                .setUserId(user.userId())
                .setUsername("   ")
                .build();

        assertThatThrownBy(() -> stub.updateUsername(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void updateUsername_whenUserDoesNotExist_shouldReturnNotFound() {
        UpdateUsernameRequest request = UpdateUsernameRequest.newBuilder()
                .setUserId("user_missing")
                .setUsername("Alice")
                .build();

        assertThatThrownBy(() -> stub.updateUsername(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.NOT_FOUND);
    }
}
