package com.spark.user.application.ping;

import com.spark.common.spring.cleanarchitecture.annotation.UseCase;

@UseCase
public class PingUseCase {
    public PingResult ping(PingCommand command) {
        return new PingResult("pong, " + command.name());
    }
}
