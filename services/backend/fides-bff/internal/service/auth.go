package service

import (
	"context"
	"errors"
	nethttp "net/http"
	"time"

	fidesbffv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/fides-bff/v1"
	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/biz"
)

type AuthService struct {
	fidesbffv1pb.UnimplementedFidesBffAuthServiceServer
	uc *biz.AuthUsecase
}

func NewAuthService(uc *biz.AuthUsecase) *AuthService {
	return &AuthService{uc: uc}
}

func (s *AuthService) SendOtp(ctx context.Context, req *fidesbffv1pb.SendOtpRequest) (*fidesbffv1pb.SendOtpResponse, error) {
	result, err := s.uc.SendOtp(ctx, biz.SendOtpCommand{
		CountryCode:    req.GetCountryCode(),
		Phone:          req.GetPhone(),
		IdempotencyKey: idempotencyKey(ctx),
	})
	if err != nil {
		return nil, toHTTPError(err)
	}
	return &fidesbffv1pb.SendOtpResponse{
		ChallengeId:    result.ChallengeID,
		ExpiresInSec:   seconds(result.ExpiresIn),
		ResendAfterSec: seconds(result.ResendAfter),
	}, nil
}

func (s *AuthService) VerifyOtp(ctx context.Context, req *fidesbffv1pb.VerifyOtpRequest) (*fidesbffv1pb.VerifyOtpResponse, error) {
	result, err := s.uc.VerifyOtp(ctx, biz.VerifyOtpCommand{
		ChallengeID:    req.GetChallengeId(),
		Code:           req.GetCode(),
		IdempotencyKey: idempotencyKey(ctx),
	})
	if err != nil {
		return nil, toHTTPError(err)
	}
	return &fidesbffv1pb.VerifyOtpResponse{
		AccessToken:         result.AccessToken,
		RefreshToken:        result.RefreshToken,
		ApplicantId:         result.ApplicantID,
		ExpiresInSec:        seconds(result.ExpiresIn),
		RefreshExpiresInSec: seconds(result.RefreshExpiresIn),
	}, nil
}

func (s *AuthService) RefreshToken(ctx context.Context, req *fidesbffv1pb.RefreshTokenRequest) (*fidesbffv1pb.RefreshTokenResponse, error) {
	result, err := s.uc.RefreshToken(ctx, biz.RefreshTokenCommand{
		RefreshToken:   req.GetRefreshToken(),
		IdempotencyKey: idempotencyKey(ctx),
	})
	if err != nil {
		return nil, toHTTPError(err)
	}
	return &fidesbffv1pb.RefreshTokenResponse{
		AccessToken:  result.AccessToken,
		ExpiresInSec: seconds(result.ExpiresIn),
	}, nil
}

func idempotencyKey(ctx context.Context) string {
	request, ok := bffkit.HTTPRequestFromContext(ctx)
	if !ok {
		return ""
	}
	return request.Header.Get(bffkit.HeaderIdempotencyKey)
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
