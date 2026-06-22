package data

import (
	"context"
	"net"
	"strings"
	"testing"
	"time"

	applicantv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/applicant/v1"
	"github.com/spark/bffkit"
	"go.opentelemetry.io/otel"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	oteltrace "go.opentelemetry.io/otel/trace"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"

	"github.com/spark/fides-bff/internal/biz"
)

func TestApplicantAuthClient_SendOtpCallsApplicantGRPC(t *testing.T) {
	server := newApplicantGRPCTestServer(t, &fakeApplicantServer{
		sendResponse: &applicantv1pb.SendOtpResponse{
			ChallengeId:    "challenge-1",
			ExpiresInSec:   300,
			ResendAfterSec: 60,
		},
	})

	client := &ApplicantAuthClient{
		resolver:    staticResolver(server.target),
		timeout:     time.Second,
		dialOptions: testDialOptions(),
	}
	result, err := client.SendOtp(context.Background(), biz.SendOtpCommand{
		CountryCode:    "+852",
		Phone:          "91234567",
		IdempotencyKey: "idem-send",
	})
	if err != nil {
		t.Fatalf("send otp: %v", err)
	}
	if result.ChallengeID != "challenge-1" || result.ExpiresIn != 300*time.Second || result.ResendAfter != 60*time.Second {
		t.Fatalf("result = %#v", result)
	}
	if server.fake.lastSend.GetCountryCode() != "+852" || server.fake.lastSend.GetPhone() != "91234567" || server.fake.lastSend.GetIdempotencyKey() != "idem-send" {
		t.Fatalf("request = %#v", server.fake.lastSend)
	}
}

func TestApplicantAuthClient_SendOtpPropagatesTraceMetadata(t *testing.T) {
	server := newApplicantGRPCTestServer(t, &fakeApplicantServer{
		sendResponse: &applicantv1pb.SendOtpResponse{
			ChallengeId:    "challenge-1",
			ExpiresInSec:   300,
			ResendAfterSec: 60,
		},
	})

	client := &ApplicantAuthClient{
		resolver:    staticResolver(server.target),
		timeout:     time.Second,
		dialOptions: testDialOptions(),
	}
	traceID := "4bf92f3577b34da6a3ce929d0e0e4736"
	spanID := "00f067aa0ba902b7"
	parsedTraceID, err := oteltrace.TraceIDFromHex(traceID)
	if err != nil {
		t.Fatalf("trace id: %v", err)
	}
	parsedSpanID, err := oteltrace.SpanIDFromHex(spanID)
	if err != nil {
		t.Fatalf("span id: %v", err)
	}
	provider := sdktrace.NewTracerProvider()
	originalProvider := otel.GetTracerProvider()
	otel.SetTracerProvider(provider)
	applicantTracer = otel.Tracer("github.com/spark/fides-bff/internal/data")
	t.Cleanup(func() {
		_ = provider.Shutdown(context.Background())
		otel.SetTracerProvider(originalProvider)
		applicantTracer = otel.Tracer("github.com/spark/fides-bff/internal/data")
	})
	ctx := oteltrace.ContextWithSpanContext(context.Background(), oteltrace.NewSpanContext(oteltrace.SpanContextConfig{
		TraceID:    parsedTraceID,
		SpanID:     parsedSpanID,
		TraceFlags: oteltrace.FlagsSampled,
	}))
	ctx = bffkit.ContextWithTraceID(ctx, traceID)
	ctx = bffkit.ContextWithCorrelationID(ctx, "corr-1")

	_, err = client.SendOtp(ctx, biz.SendOtpCommand{
		CountryCode:    "+852",
		Phone:          "91234567",
		IdempotencyKey: "idem-send",
	})
	if err != nil {
		t.Fatalf("send otp: %v", err)
	}

	if got := server.fake.lastMetadata.Get("x-trace-id"); len(got) != 1 || got[0] != traceID {
		t.Fatalf("x-trace-id = %#v, want %s", got, traceID)
	}
	if got := server.fake.lastMetadata.Get("traceparent"); len(got) != 1 || !strings.HasPrefix(got[0], "00-"+traceID+"-") {
		t.Fatalf("traceparent = %#v, want W3C trace context with trace id %s", got, traceID)
	} else if got[0] == "00-"+traceID+"-"+spanID+"-01" {
		t.Fatalf("traceparent span id was not advanced by the BFF client span: %s", got[0])
	}
}

func TestApplicantAuthClient_VerifyOtpMapsApplicantResponse(t *testing.T) {
	server := newApplicantGRPCTestServer(t, &fakeApplicantServer{
		verifyResponse: &applicantv1pb.VerifyOtpResponse{
			AccessToken:         "access-token",
			RefreshToken:        "refresh-token",
			ApplicantId:         "applicant-1",
			ExpiresInSec:        3600,
			RefreshExpiresInSec: 7200,
		},
	})

	client := &ApplicantAuthClient{
		resolver:    staticResolver(server.target),
		timeout:     time.Second,
		dialOptions: testDialOptions(),
	}
	result, err := client.VerifyOtp(context.Background(), biz.VerifyOtpCommand{
		ChallengeID:    "challenge-1",
		Code:           "123456",
		IdempotencyKey: "idem-verify",
	})
	if err != nil {
		t.Fatalf("verify otp: %v", err)
	}
	if result.AccessToken != "access-token" || result.RefreshToken != "refresh-token" || result.ApplicantID != "applicant-1" {
		t.Fatalf("result = %#v", result)
	}
	if result.ExpiresIn != time.Hour || result.RefreshExpiresIn != 2*time.Hour {
		t.Fatalf("durations = %#v", result)
	}
	if server.fake.lastVerify.GetChallengeId() != "challenge-1" || server.fake.lastVerify.GetCode() != "123456" || server.fake.lastVerify.GetIdempotencyKey() != "idem-verify" {
		t.Fatalf("request = %#v", server.fake.lastVerify)
	}
}

func TestApplicantAuthClient_RefreshTokenMapsApplicantResponse(t *testing.T) {
	server := newApplicantGRPCTestServer(t, &fakeApplicantServer{
		refreshResponse: &applicantv1pb.RefreshTokenResponse{
			AccessToken:  "new-access-token",
			ExpiresInSec: 1800,
		},
	})

	client := &ApplicantAuthClient{
		resolver:    staticResolver(server.target),
		timeout:     time.Second,
		dialOptions: testDialOptions(),
	}
	result, err := client.RefreshToken(context.Background(), biz.RefreshTokenCommand{
		RefreshToken:   "refresh-token",
		IdempotencyKey: "idem-refresh",
	})
	if err != nil {
		t.Fatalf("refresh token: %v", err)
	}
	if result.AccessToken != "new-access-token" || result.ExpiresIn != 30*time.Minute {
		t.Fatalf("result = %#v", result)
	}
	if server.fake.lastRefresh.GetRefreshToken() != "refresh-token" || server.fake.lastRefresh.GetIdempotencyKey() != "idem-refresh" {
		t.Fatalf("request = %#v", server.fake.lastRefresh)
	}
}

func TestApplicantAuthClient_MapsApplicantGRPCErrors(t *testing.T) {
	tests := []struct {
		name     string
		err      error
		wantCode string
	}{
		{"invalid code", status.Error(codes.InvalidArgument, "otp_code_invalid"), biz.AuthCodeInvalid},
		{"expired code", status.Error(codes.FailedPrecondition, "otp_code_expired"), biz.AuthCodeExpired},
		{"too many attempts", status.Error(codes.ResourceExhausted, "otp_too_many_attempts"), biz.AuthCodeTooManyAttempts},
		{"token expired", status.Error(codes.Unauthenticated, "token_expired"), biz.AuthCodeTokenExpired},
		{"unavailable", status.Error(codes.Unavailable, "otp_provider_disabled"), biz.AuthCodeApplicantUnavailable},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := newApplicantGRPCTestServer(t, &fakeApplicantServer{verifyErr: tt.err})
			client := &ApplicantAuthClient{
				resolver:    staticResolver(server.target),
				timeout:     time.Second,
				dialOptions: testDialOptions(),
			}
			_, err := client.VerifyOtp(context.Background(), biz.VerifyOtpCommand{
				ChallengeID:    "challenge-1",
				Code:           "123456",
				IdempotencyKey: "idem-verify",
			})
			authErr, ok := err.(*biz.AuthError)
			if !ok {
				t.Fatalf("err = %#v, want AuthError", err)
			}
			if authErr.Code != tt.wantCode {
				t.Fatalf("code = %q, want %q", authErr.Code, tt.wantCode)
			}
		})
	}
}

type applicantGRPCTestServer struct {
	target string
	fake   *fakeApplicantServer
}

func newApplicantGRPCTestServer(t *testing.T, fake *fakeApplicantServer) applicantGRPCTestServer {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := grpc.NewServer()
	applicantv1pb.RegisterApplicantAuthServiceServer(srv, fake)
	go func() {
		_ = srv.Serve(listener)
	}()
	t.Cleanup(func() {
		srv.Stop()
		_ = listener.Close()
	})
	return applicantGRPCTestServer{target: listener.Addr().String(), fake: fake}
}

type fakeApplicantServer struct {
	applicantv1pb.UnimplementedApplicantAuthServiceServer
	sendResponse    *applicantv1pb.SendOtpResponse
	verifyResponse  *applicantv1pb.VerifyOtpResponse
	refreshResponse *applicantv1pb.RefreshTokenResponse
	verifyErr       error
	lastSend        *applicantv1pb.SendOtpRequest
	lastVerify      *applicantv1pb.VerifyOtpRequest
	lastRefresh     *applicantv1pb.RefreshTokenRequest
	lastMetadata    metadata.MD
}

func (s *fakeApplicantServer) SendOtp(ctx context.Context, req *applicantv1pb.SendOtpRequest) (*applicantv1pb.SendOtpResponse, error) {
	s.lastSend = req
	s.lastMetadata, _ = metadata.FromIncomingContext(ctx)
	return s.sendResponse, nil
}

func (s *fakeApplicantServer) VerifyOtp(ctx context.Context, req *applicantv1pb.VerifyOtpRequest) (*applicantv1pb.VerifyOtpResponse, error) {
	s.lastVerify = req
	s.lastMetadata, _ = metadata.FromIncomingContext(ctx)
	if s.verifyErr != nil {
		return nil, s.verifyErr
	}
	return s.verifyResponse, nil
}

func (s *fakeApplicantServer) RefreshToken(ctx context.Context, req *applicantv1pb.RefreshTokenRequest) (*applicantv1pb.RefreshTokenResponse, error) {
	s.lastRefresh = req
	s.lastMetadata, _ = metadata.FromIncomingContext(ctx)
	return s.refreshResponse, nil
}

type staticResolver string

func (r staticResolver) Resolve(context.Context) (string, error) {
	return string(r), nil
}

func testDialOptions() []grpc.DialOption {
	return []grpc.DialOption{grpc.WithTransportCredentials(insecure.NewCredentials())}
}
