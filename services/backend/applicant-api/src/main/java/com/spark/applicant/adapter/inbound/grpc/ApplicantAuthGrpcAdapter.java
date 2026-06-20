package com.spark.applicant.adapter.inbound.grpc;

import com.spark.applicant.application.auth.ApplicantAuthException;
import com.spark.applicant.application.auth.RefreshTokenCommand;
import com.spark.applicant.application.auth.RefreshTokenResult;
import com.spark.applicant.application.auth.RefreshTokenUseCase;
import com.spark.applicant.application.auth.SendOtpCommand;
import com.spark.applicant.application.auth.SendOtpResult;
import com.spark.applicant.application.auth.SendOtpUseCase;
import com.spark.applicant.application.auth.VerifyOtpCommand;
import com.spark.applicant.application.auth.VerifyOtpResult;
import com.spark.applicant.application.auth.VerifyOtpUseCase;
import com.spark.common.spring.cleanarchitecture.annotation.InboundAdapter;
import com.vesta.lendora.applicant.v1.ApplicantAuthServiceGrpc;
import com.vesta.lendora.applicant.v1.RefreshTokenRequest;
import com.vesta.lendora.applicant.v1.RefreshTokenResponse;
import com.vesta.lendora.applicant.v1.SendOtpRequest;
import com.vesta.lendora.applicant.v1.SendOtpResponse;
import com.vesta.lendora.applicant.v1.VerifyOtpRequest;
import com.vesta.lendora.applicant.v1.VerifyOtpResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;

@InboundAdapter
public class ApplicantAuthGrpcAdapter extends ApplicantAuthServiceGrpc.ApplicantAuthServiceImplBase {
    private final SendOtpUseCase sendOtpUseCase;
    private final VerifyOtpUseCase verifyOtpUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final ApplicantAuthTelemetry telemetry;

    public ApplicantAuthGrpcAdapter(
            SendOtpUseCase sendOtpUseCase,
            VerifyOtpUseCase verifyOtpUseCase,
            RefreshTokenUseCase refreshTokenUseCase,
            MeterRegistry meterRegistry) {
        this.sendOtpUseCase = sendOtpUseCase;
        this.verifyOtpUseCase = verifyOtpUseCase;
        this.refreshTokenUseCase = refreshTokenUseCase;
        this.telemetry = new ApplicantAuthTelemetry(meterRegistry);
    }

    @Override
    public void sendOtp(SendOtpRequest request, StreamObserver<SendOtpResponse> responseObserver) {
        try {
            SendOtpResult result = telemetry.record("send_otp", () -> sendOtpUseCase.sendOtp(
                    new SendOtpCommand(request.getCountryCode(), request.getPhone(), request.getIdempotencyKey())));
            responseObserver.onNext(SendOtpResponse.newBuilder()
                    .setChallengeId(result.challengeId())
                    .setExpiresInSec(Math.toIntExact(result.expiresIn().toSeconds()))
                    .setResendAfterSec(Math.toIntExact(result.resendAfter().toSeconds()))
                    .build());
            responseObserver.onCompleted();
        } catch (ApplicantAuthException error) {
            responseObserver.onError(toStatus(error).withDescription(error.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void verifyOtp(VerifyOtpRequest request, StreamObserver<VerifyOtpResponse> responseObserver) {
        try {
            VerifyOtpResult result = telemetry.record("verify_otp", () -> verifyOtpUseCase.verifyOtp(
                    new VerifyOtpCommand(request.getChallengeId(), request.getCode(), request.getIdempotencyKey())));
            responseObserver.onNext(VerifyOtpResponse.newBuilder()
                    .setAccessToken(result.accessToken())
                    .setRefreshToken(result.refreshToken())
                    .setApplicantId(result.applicantId())
                    .setExpiresInSec(Math.toIntExact(result.expiresIn().toSeconds()))
                    .setRefreshExpiresInSec(Math.toIntExact(result.refreshExpiresIn().toSeconds()))
                    .build());
            responseObserver.onCompleted();
        } catch (ApplicantAuthException error) {
            responseObserver.onError(toStatus(error).withDescription(error.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void refreshToken(RefreshTokenRequest request, StreamObserver<RefreshTokenResponse> responseObserver) {
        try {
            RefreshTokenResult result = telemetry.record("refresh_token", () -> refreshTokenUseCase.refreshToken(
                    new RefreshTokenCommand(request.getRefreshToken(), request.getIdempotencyKey())));
            responseObserver.onNext(RefreshTokenResponse.newBuilder()
                    .setAccessToken(result.accessToken())
                    .setExpiresInSec(Math.toIntExact(result.expiresIn().toSeconds()))
                    .build());
            responseObserver.onCompleted();
        } catch (ApplicantAuthException error) {
            responseObserver.onError(toStatus(error).withDescription(error.getMessage()).asRuntimeException());
        }
    }

    private Status toStatus(ApplicantAuthException error) {
        return switch (error.getMessage()) {
            case "unsupported_country", "idempotency_key_required", "otp_code_invalid",
                    "idempotency_key_conflict" -> Status.INVALID_ARGUMENT;
            case "otp_cooldown_active", "otp_code_expired" -> Status.FAILED_PRECONDITION;
            case "otp_provider_disabled" -> Status.UNAVAILABLE;
            case "otp_too_many_attempts" -> Status.RESOURCE_EXHAUSTED;
            case "token_invalid", "token_expired" -> Status.UNAUTHENTICATED;
            default -> Status.UNKNOWN;
        };
    }
}
