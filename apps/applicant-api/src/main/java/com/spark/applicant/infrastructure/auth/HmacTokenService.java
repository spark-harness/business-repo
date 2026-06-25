package com.spark.applicant.infrastructure.auth;

import com.spark.applicant.application.auth.port.TokenService;
import com.spark.applicant.bootstrap.ApplicantAuthProperties;
import com.spark.common.spring.cleanarchitecture.annotation.InfrastructureAdapter;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@InfrastructureAdapter
@ConditionalOnProperty(prefix = "spark.applicant.auth", name = "token-mode", havingValue = "hmac")
public class HmacTokenService implements TokenService {
    private final Clock clock;
    private final String tokenSecret;

    public HmacTokenService(Clock clock, ApplicantAuthProperties properties) {
        this.clock = clock;
        this.tokenSecret = properties.getTokenSecret();
    }

    @Override
    public String issueAccessToken(String applicantId) {
        return issueToken("access", applicantId);
    }

    @Override
    public String issueRefreshToken(String applicantId) {
        return issueToken("refresh", applicantId);
    }

    private String issueToken(String tokenType, String applicantId) {
        String payload = encode(tokenType + ":" + applicantId + ":" + clock.millis() + ":" + UUID.randomUUID());
        return tokenType + "_v1." + payload + "." + sign(payload);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return encode(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (InvalidKeyException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("hmac token cannot be signed", error);
        }
    }

    private String encode(String value) {
        return encode(value.getBytes(StandardCharsets.UTF_8));
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
