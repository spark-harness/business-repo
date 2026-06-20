package com.spark.applicant.bootstrap;

import com.spark.applicant.application.auth.AuthPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spark.applicant.auth")
public class ApplicantAuthProperties {
    private RuntimeStore runtimeStore = RuntimeStore.IN_MEMORY;
    private OtpProvider otpProvider = OtpProvider.TEST;
    private TokenMode tokenMode = TokenMode.SIMPLE;
    private String tokenSecret = "";
    private String keyPrefix = "applicant-api";
    private boolean initializeSchema = false;
    private String jdbcUrl = "";
    private String jdbcUsername = "";
    private String jdbcPassword = "";
    private String supportedCountryCode = "+852";
    private Duration otpTtl = Duration.ofMinutes(5);
    private Duration resendCooldown = Duration.ofSeconds(60);
    private int maxOtpAttempts = 5;
    private Duration lockDuration = Duration.ofMinutes(15);
    private Duration accessTokenTtl = Duration.ofHours(1);
    private Duration refreshTokenTtl = Duration.ofHours(1);
    private Duration idempotencyTtl = Duration.ofHours(1);

    public AuthPolicy toPolicy() {
        return new AuthPolicy(
                supportedCountryCode,
                otpTtl,
                resendCooldown,
                maxOtpAttempts,
                lockDuration,
                accessTokenTtl,
                refreshTokenTtl,
                idempotencyTtl);
    }

    public RuntimeStore getRuntimeStore() {
        return runtimeStore;
    }

    public void setRuntimeStore(RuntimeStore runtimeStore) {
        this.runtimeStore = runtimeStore;
    }

    public OtpProvider getOtpProvider() {
        return otpProvider;
    }

    public void setOtpProvider(OtpProvider otpProvider) {
        this.otpProvider = otpProvider;
    }

    public TokenMode getTokenMode() {
        return tokenMode;
    }

    public void setTokenMode(TokenMode tokenMode) {
        this.tokenMode = tokenMode;
    }

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getJdbcUsername() {
        return jdbcUsername;
    }

    public void setJdbcUsername(String jdbcUsername) {
        this.jdbcUsername = jdbcUsername;
    }

    public String getJdbcPassword() {
        return jdbcPassword;
    }

    public void setJdbcPassword(String jdbcPassword) {
        this.jdbcPassword = jdbcPassword;
    }

    public String getSupportedCountryCode() {
        return supportedCountryCode;
    }

    public void setSupportedCountryCode(String supportedCountryCode) {
        this.supportedCountryCode = supportedCountryCode;
    }

    public Duration getOtpTtl() {
        return otpTtl;
    }

    public void setOtpTtl(Duration otpTtl) {
        this.otpTtl = otpTtl;
    }

    public Duration getResendCooldown() {
        return resendCooldown;
    }

    public void setResendCooldown(Duration resendCooldown) {
        this.resendCooldown = resendCooldown;
    }

    public int getMaxOtpAttempts() {
        return maxOtpAttempts;
    }

    public void setMaxOtpAttempts(int maxOtpAttempts) {
        this.maxOtpAttempts = maxOtpAttempts;
    }

    public Duration getLockDuration() {
        return lockDuration;
    }

    public void setLockDuration(Duration lockDuration) {
        this.lockDuration = lockDuration;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }

    public enum RuntimeStore {
        IN_MEMORY,
        REDIS_JDBC
    }

    public enum OtpProvider {
        TEST,
        DISABLED
    }

    public enum TokenMode {
        SIMPLE,
        HMAC
    }
}
