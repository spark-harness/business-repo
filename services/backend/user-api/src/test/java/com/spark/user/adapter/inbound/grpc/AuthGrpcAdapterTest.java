package com.spark.user.adapter.inbound.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spark.user.application.auth.RegisterOrLoginUseCase;
import com.spark.user.infrastructure.auth.FixedVerificationCodeVerifier;
import com.spark.user.infrastructure.auth.InMemoryUserRepository;
import com.vesta.spark.user.v1.AuthServiceGrpc;
import com.vesta.spark.user.v1.RegisterOrLoginByMobileCodeRequest;
import com.vesta.spark.user.v1.RegisterOrLoginByMobileCodeResponse;
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

class AuthGrpcAdapterTest {
    private InMemoryUserRepository userRepository;
    private RegisterOrLoginUseCase useCase;
    private io.grpc.Server server;
    private ManagedChannel channel;
    private AuthServiceGrpc.AuthServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        String serverName = "auth-test-" + UUID.randomUUID();
        userRepository = new InMemoryUserRepository();
        useCase = new RegisterOrLoginUseCase(userRepository, new FixedVerificationCodeVerifier());
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new AuthGrpcAdapter(useCase))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
        stub = AuthServiceGrpc.newBlockingStub(channel);
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
    void registerOrLoginByMobileCode_whenMobileIsNew_shouldReturnNewUser() {
        RegisterOrLoginByMobileCodeResponse response = stub.registerOrLoginByMobileCode(
                RegisterOrLoginByMobileCodeRequest.newBuilder()
                        .setMobile("13800138000")
                        .setVerificationCode("123456")
                        .build());

        assertThat(response.getUserId()).isNotBlank();
        assertThat(response.getNewUser()).isTrue();
    }

    @Test
    void registerOrLoginByMobileCode_whenMobileExists_shouldReturnExistingUser() {
        RegisterOrLoginByMobileCodeRequest request = RegisterOrLoginByMobileCodeRequest.newBuilder()
                .setMobile("13800138000")
                .setVerificationCode("123456")
                .build();

        RegisterOrLoginByMobileCodeResponse first = stub.registerOrLoginByMobileCode(request);
        RegisterOrLoginByMobileCodeResponse second = stub.registerOrLoginByMobileCode(request);

        assertThat(second.getUserId()).isEqualTo(first.getUserId());
        assertThat(second.getNewUser()).isFalse();
    }

    @Test
    void registerOrLoginByMobileCode_whenMobileIsInvalid_shouldReturnInvalidArgument() {
        RegisterOrLoginByMobileCodeRequest request = RegisterOrLoginByMobileCodeRequest.newBuilder()
                .setMobile("12345")
                .setVerificationCode("123456")
                .build();

        assertThatThrownBy(() -> stub.registerOrLoginByMobileCode(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void registerOrLoginByMobileCode_whenCodeIsWrong_shouldReturnInvalidArgument() {
        RegisterOrLoginByMobileCodeRequest request = RegisterOrLoginByMobileCodeRequest.newBuilder()
                .setMobile("13800138000")
                .setVerificationCode("000000")
                .build();

        assertThatThrownBy(() -> stub.registerOrLoginByMobileCode(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void registerOrLoginByMobileCode_whenUserIsDisabled_shouldReturnPermissionDenied() {
        RegisterOrLoginByMobileCodeRequest request = RegisterOrLoginByMobileCodeRequest.newBuilder()
                .setMobile("13800138000")
                .setVerificationCode("123456")
                .build();
        RegisterOrLoginByMobileCodeResponse user = stub.registerOrLoginByMobileCode(request);
        userRepository.updateEnabled(user.getUserId(), false);

        assertThatThrownBy(() -> stub.registerOrLoginByMobileCode(request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }
}
