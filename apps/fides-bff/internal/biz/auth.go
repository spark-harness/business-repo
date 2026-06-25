package biz

import (
	"context"
	"time"
)

const (
	AuthCodeUnsupportedCountry   = "unsupported_country"
	AuthCodeCooldownActive       = "otp_cooldown_active"
	AuthCodeInvalid              = "code_invalid"
	AuthCodeExpired              = "code_expired"
	AuthCodeTooManyAttempts      = "too_many_attempts"
	AuthCodeTokenExpired         = "token_expired"
	AuthCodeApplicantUnavailable = "applicant_unavailable"
)

type ApplicantAuthClient interface {
	SendOtp(context.Context, SendOtpCommand) (SendOtpResult, error)
	VerifyOtp(context.Context, VerifyOtpCommand) (VerifyOtpResult, error)
	RefreshToken(context.Context, RefreshTokenCommand) (RefreshTokenResult, error)
}

type SendOtpCommand struct {
	CountryCode    string
	Phone          string
	IdempotencyKey string
}

type SendOtpResult struct {
	ChallengeID string
	ExpiresIn   time.Duration
	ResendAfter time.Duration
}

type VerifyOtpCommand struct {
	ChallengeID    string
	Code           string
	IdempotencyKey string
}

type VerifyOtpResult struct {
	AccessToken      string
	RefreshToken     string
	ApplicantID      string
	ExpiresIn        time.Duration
	RefreshExpiresIn time.Duration
}

type RefreshTokenCommand struct {
	RefreshToken   string
	IdempotencyKey string
}

type RefreshTokenResult struct {
	AccessToken string
	ExpiresIn   time.Duration
}

type AuthError struct {
	Code          string
	Message       string
	RetryAfterSec int
}

func (e *AuthError) Error() string {
	if e.Message != "" {
		return e.Message
	}
	return e.Code
}

type AuthUsecase struct {
	client ApplicantAuthClient
}

func NewAuthUsecase(client ApplicantAuthClient) *AuthUsecase {
	return &AuthUsecase{client: client}
}

func (uc *AuthUsecase) SendOtp(ctx context.Context, command SendOtpCommand) (SendOtpResult, error) {
	return uc.client.SendOtp(ctx, command)
}

func (uc *AuthUsecase) VerifyOtp(ctx context.Context, command VerifyOtpCommand) (VerifyOtpResult, error) {
	return uc.client.VerifyOtp(ctx, command)
}

func (uc *AuthUsecase) RefreshToken(ctx context.Context, command RefreshTokenCommand) (RefreshTokenResult, error) {
	return uc.client.RefreshToken(ctx, command)
}
