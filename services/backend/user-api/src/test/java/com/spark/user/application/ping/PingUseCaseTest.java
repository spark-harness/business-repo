package com.spark.user.application.ping;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PingUseCaseTest {
    private final PingUseCase pingUseCase = new PingUseCase();

    @Test
    void ping_whenNameIsForest_shouldReturnPongMessage() {
        PingResult result = pingUseCase.ping(new PingCommand("forest"));

        assertThat(result.message()).isEqualTo("pong, forest");
    }

    @Test
    void ping_whenNameIsAlice_shouldReturnPongMessage() {
        PingResult result = pingUseCase.ping(new PingCommand("Alice"));

        assertThat(result.message()).isEqualTo("pong, Alice");
    }
}
