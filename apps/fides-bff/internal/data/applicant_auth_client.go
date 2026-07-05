package data

import (
	"context"
	"crypto/tls"
	"errors"
	"time"

	applicantv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/applicant/v1"
	"github.com/spark/bffkit"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	oteltrace "go.opentelemetry.io/otel/trace"
	"google.golang.org/grpc"
	grpccodes "google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/conf"
)

var grpcNewClient = grpc.NewClient

var errNoHealthyApplicant = errors.New("no healthy applicant-api instance")

var applicantTracer = otel.Tracer("github.com/spark/fides-bff/internal/data")

type ServiceResolver interface {
	Resolve(context.Context) (string, error)
}

type ApplicantAuthClient struct {
	resolver    ServiceResolver
	timeout     time.Duration
	dialOptions []grpc.DialOption
}

func NewApplicantAuthClient(c *conf.Applicant) *ApplicantAuthClient {
	timeout := 3 * time.Second
	plaintext := true
	target := ""
	if c != nil {
		if parsed, err := time.ParseDuration(c.GRPC.Timeout); err == nil && parsed > 0 {
			timeout = parsed
		}
		plaintext = c.GRPC.Plaintext
		target = c.GRPC.Target
	}
	opts := []grpc.DialOption{}
	if plaintext {
		opts = append(opts, grpc.WithTransportCredentials(insecure.NewCredentials()))
	} else {
		opts = append(opts, grpc.WithTransportCredentials(credentials.NewTLS(&tls.Config{})))
	}
	return &ApplicantAuthClient{
		resolver:    grpcResolver(target, NewConsulResolver(c)),
		timeout:     timeout,
		dialOptions: opts,
	}
}

func (c *ApplicantAuthClient) SendOtp(ctx context.Context, command biz.SendOtpCommand) (biz.SendOtpResult, error) {
	client, cleanup, err := c.client(ctx)
	if err != nil {
		return biz.SendOtpResult{}, err
	}
	defer cleanup()

	rpcCtx, cancel, endSpan := c.rpcContext(ctx, "SendOtp")
	defer cancel()
	resp, err := client.SendOtp(rpcCtx, &applicantv1pb.SendOtpRequest{
		CountryCode:    command.CountryCode,
		Phone:          command.Phone,
		IdempotencyKey: command.IdempotencyKey,
	})
	endSpan(err)
	if err != nil {
		return biz.SendOtpResult{}, authErrorFromGRPC(err)
	}
	return biz.SendOtpResult{
		ChallengeID: resp.GetChallengeId(),
		ExpiresIn:   seconds(resp.GetExpiresInSec()),
		ResendAfter: seconds(resp.GetResendAfterSec()),
	}, nil
}

func (c *ApplicantAuthClient) VerifyOtp(ctx context.Context, command biz.VerifyOtpCommand) (biz.VerifyOtpResult, error) {
	client, cleanup, err := c.client(ctx)
	if err != nil {
		return biz.VerifyOtpResult{}, err
	}
	defer cleanup()

	rpcCtx, cancel, endSpan := c.rpcContext(ctx, "VerifyOtp")
	defer cancel()
	resp, err := client.VerifyOtp(rpcCtx, &applicantv1pb.VerifyOtpRequest{
		ChallengeId:    command.ChallengeID,
		Code:           command.Code,
		IdempotencyKey: command.IdempotencyKey,
	})
	endSpan(err)
	if err != nil {
		return biz.VerifyOtpResult{}, authErrorFromGRPC(err)
	}
	return biz.VerifyOtpResult{
		AccessToken:      resp.GetAccessToken(),
		RefreshToken:     resp.GetRefreshToken(),
		ApplicantID:      resp.GetApplicantId(),
		ExpiresIn:        seconds(resp.GetExpiresInSec()),
		RefreshExpiresIn: seconds(resp.GetRefreshExpiresInSec()),
	}, nil
}

func (c *ApplicantAuthClient) RefreshToken(ctx context.Context, command biz.RefreshTokenCommand) (biz.RefreshTokenResult, error) {
	client, cleanup, err := c.client(ctx)
	if err != nil {
		return biz.RefreshTokenResult{}, err
	}
	defer cleanup()

	rpcCtx, cancel, endSpan := c.rpcContext(ctx, "RefreshToken")
	defer cancel()
	resp, err := client.RefreshToken(rpcCtx, &applicantv1pb.RefreshTokenRequest{
		RefreshToken:   command.RefreshToken,
		IdempotencyKey: command.IdempotencyKey,
	})
	endSpan(err)
	if err != nil {
		return biz.RefreshTokenResult{}, authErrorFromGRPC(err)
	}
	return biz.RefreshTokenResult{
		AccessToken: resp.GetAccessToken(),
		ExpiresIn:   seconds(resp.GetExpiresInSec()),
	}, nil
}

func (c *ApplicantAuthClient) client(ctx context.Context) (applicantv1pb.ApplicantAuthServiceClient, func(), error) {
	if c == nil || c.resolver == nil {
		return nil, func() {}, unavailable()
	}
	target, err := c.resolver.Resolve(ctx)
	if err != nil {
		return nil, func() {}, unavailable()
	}
	conn, err := grpcNewClient(target, c.dialOptions...)
	if err != nil {
		return nil, func() {}, unavailable()
	}
	return applicantv1pb.NewApplicantAuthServiceClient(conn), func() { _ = conn.Close() }, nil
}

func (c *ApplicantAuthClient) rpcContext(ctx context.Context, method string) (context.Context, context.CancelFunc, func(error)) {
	ctx, span := applicantTracer.Start(ctx,
		"ApplicantAuthService/"+method,
		oteltrace.WithSpanKind(oteltrace.SpanKindClient),
		oteltrace.WithAttributes(
			attribute.String("rpc.system", "grpc"),
			attribute.String("rpc.service", "vesta.lendora.applicant.v1.ApplicantAuthService"),
			attribute.String("rpc.method", method),
		),
	)
	ctx = bffkit.OutgoingGRPCContext(ctx)
	endSpan := func(err error) {
		if err != nil {
			span.SetStatus(codes.Error, status.Code(err).String())
			span.SetAttributes(attribute.String("rpc.grpc.status_code", status.Code(err).String()))
		}
		span.End()
	}
	if c == nil || c.timeout <= 0 {
		return ctx, func() {}, endSpan
	}
	timeoutCtx, cancel := context.WithTimeout(ctx, c.timeout)
	return timeoutCtx, cancel, endSpan
}

func unavailable() error {
	return &biz.AuthError{Code: biz.AuthCodeApplicantUnavailable, Message: "applicant-api is unavailable"}
}

func authErrorFromGRPC(err error) error {
	st, ok := status.FromError(err)
	if !ok {
		return unavailable()
	}
	switch st.Code() {
	case grpccodes.Unavailable, grpccodes.DeadlineExceeded:
		return unavailable()
	}
	switch st.Message() {
	case "unsupported_country":
		return &biz.AuthError{Code: biz.AuthCodeUnsupportedCountry, Message: "unsupported country"}
	case "otp_cooldown_active":
		return &biz.AuthError{Code: biz.AuthCodeCooldownActive, Message: "otp cooldown active"}
	case "otp_code_invalid":
		return &biz.AuthError{Code: biz.AuthCodeInvalid, Message: "invalid otp code"}
	case "otp_code_expired":
		return &biz.AuthError{Code: biz.AuthCodeExpired, Message: "expired otp code"}
	case "otp_too_many_attempts":
		return &biz.AuthError{Code: biz.AuthCodeTooManyAttempts, Message: "too many attempts"}
	case "token_expired", "token_invalid":
		return &biz.AuthError{Code: biz.AuthCodeTokenExpired, Message: "token expired"}
	default:
		return unavailable()
	}
}

func seconds(value int32) time.Duration {
	return time.Duration(value) * time.Second
}
