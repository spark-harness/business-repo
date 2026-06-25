package com.spark.applicant.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.spark.applicant.application.auth.port.OtpChallengeRepository;
import com.spark.applicant.application.runtime.RuntimeDependencyProbe;
import com.spark.applicant.infrastructure.auth.RedisOtpChallengeRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        properties = {
            "spark.grpc.server.enabled=false",
            "spark.applicant.auth.runtime-store=redis-jdbc",
            "spark.applicant.auth.token-mode=hmac",
            "spark.applicant.auth.token-secret=test-secret",
            "spark.applicant.auth.jdbc-url=jdbc:h2:mem:applicant-wiring;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spark.applicant.auth.jdbc-username=sa",
            "spark.applicant.auth.jdbc-password=",
            "spark.applicant.auth.migrations-enabled=true",
            "spark.applicant.auth.consul.enabled=false",
            "spring.data.redis.host=localhost"
        })
class RedisJdbcApplicationWiringTest {
    @Autowired
    private OtpChallengeRepository otpChallengeRepository;

    @Autowired
    private List<RuntimeDependencyProbe> runtimeDependencyProbes;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void applicationContext_whenRedisJdbcRuntimeIsEnabled_shouldWireRedisRepositories() {
        assertThat(otpChallengeRepository).isInstanceOf(RedisOtpChallengeRepository.class);
        assertThat(runtimeDependencyProbes)
                .extracting(probe -> probe.getClass().getSimpleName())
                .contains("JdbcRuntimeDependencyProbe", "RedisRuntimeDependencyProbe");
        Integer migrationCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from "flyway_schema_history"
                where "version" = '1'
                  and "description" = 'create applicants'
                  and "success" = true
                """,
                Integer.class);
        assertThat(migrationCount).isEqualTo(1);
    }
}
