package service

import (
	"errors"
	nethttp "net/http"
	"time"

	"github.com/go-kratos/kratos/v2/transport/http"
	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/biz"
)

type AuthService struct {
	uc *biz.AuthUsecase
}

func NewAuthService(uc *biz.AuthUsecase) *AuthService {
	return &AuthService{uc: uc}
}

type sendOtpRequest struct {
	CountryCode string `json:"countryCode"`
	Phone       string `json:"phone"`
}

type sendOtpResponse struct {
	ChallengeID    string `json:"challengeId"`
	ExpiresInSec   int32  `json:"expiresInSec"`
	ResendAfterSec int32  `json:"resendAfterSec"`
}

type verifyOtpRequest struct {
	ChallengeID string `json:"challengeId"`
	Code        string `json:"code"`
}

type verifyOtpResponse struct {
	AccessToken         string `json:"accessToken"`
	RefreshToken        string `json:"refreshToken,omitempty"`
	ApplicantID         string `json:"applicantId"`
	ExpiresInSec        int32  `json:"expiresInSec"`
	RefreshExpiresInSec int32  `json:"refreshExpiresInSec,omitempty"`
}

type refreshTokenRequest struct {
	RefreshToken string `json:"refreshToken"`
}

type refreshTokenResponse struct {
	AccessToken  string `json:"accessToken"`
	ExpiresInSec int32  `json:"expiresInSec"`
}

func (s *AuthService) SendOtp(ctx http.Context) error {
	var req sendOtpRequest
	if err := ctx.Bind(&req); err != nil {
		return err
	}
	result, err := s.uc.SendOtp(ctx, biz.SendOtpCommand{
		CountryCode:    req.CountryCode,
		Phone:          req.Phone,
		IdempotencyKey: idempotencyKey(ctx),
	})
	if err != nil {
		return toHTTPError(err)
	}
	return ctx.JSON(nethttp.StatusOK, sendOtpResponse{
		ChallengeID:    result.ChallengeID,
		ExpiresInSec:   seconds(result.ExpiresIn),
		ResendAfterSec: seconds(result.ResendAfter),
	})
}

func (s *AuthService) VerifyOtp(ctx http.Context) error {
	var req verifyOtpRequest
	if err := ctx.Bind(&req); err != nil {
		return err
	}
	result, err := s.uc.VerifyOtp(ctx, biz.VerifyOtpCommand{
		ChallengeID:    req.ChallengeID,
		Code:           req.Code,
		IdempotencyKey: idempotencyKey(ctx),
	})
	if err != nil {
		return toHTTPError(err)
	}
	return ctx.JSON(nethttp.StatusOK, verifyOtpResponse{
		AccessToken:         result.AccessToken,
		RefreshToken:        result.RefreshToken,
		ApplicantID:         result.ApplicantID,
		ExpiresInSec:        seconds(result.ExpiresIn),
		RefreshExpiresInSec: seconds(result.RefreshExpiresIn),
	})
}

func (s *AuthService) RefreshToken(ctx http.Context) error {
	var req refreshTokenRequest
	if err := ctx.Bind(&req); err != nil {
		return err
	}
	result, err := s.uc.RefreshToken(ctx, biz.RefreshTokenCommand{
		RefreshToken:   req.RefreshToken,
		IdempotencyKey: idempotencyKey(ctx),
	})
	if err != nil {
		return toHTTPError(err)
	}
	return ctx.JSON(nethttp.StatusOK, refreshTokenResponse{
		AccessToken:  result.AccessToken,
		ExpiresInSec: seconds(result.ExpiresIn),
	})
}

func idempotencyKey(ctx http.Context) string {
	return ctx.Request().Header.Get(bffkit.HeaderIdempotencyKey)
}

func seconds(value time.Duration) int32 {
	return int32(value / time.Second)
}

func toHTTPError(err error) error {
	var authErr *biz.AuthError
	if !errors.As(err, &authErr) {
		return err
	}
	httpErr := &bffkit.HTTPError{
		Status:        nethttp.StatusUnprocessableEntity,
		Code:          authErr.Code,
		Message:       authErr.Message,
		RetryAfterSec: authErr.RetryAfterSec,
	}
	switch authErr.Code {
	case biz.AuthCodeCooldownActive, biz.AuthCodeTooManyAttempts:
		httpErr.Status = nethttp.StatusTooManyRequests
	case biz.AuthCodeTokenExpired:
		httpErr.Status = nethttp.StatusUnauthorized
	case biz.AuthCodeApplicantUnavailable:
		httpErr.Status = nethttp.StatusServiceUnavailable
	}
	return httpErr
}
