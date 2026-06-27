package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/go-kratos/kratos/v2/errors"
	"github.com/go-kratos/kratos/v2/log"
	khttp "github.com/go-kratos/kratos/v2/transport/http"
	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/conf"
	"github.com/spark/fides-bff/internal/data"
	"github.com/spark/fides-bff/internal/service"
)

// TestHTTPServer_Health_returnsStatusAndVersion proves AC1: GET /api/v1/health
// returns 200 with a JSON body carrying the service status and build version,
// and that the route is actually wired under the /api/v1 prefix (BR1).
func TestHTTPServer_Health_returnsStatusAndVersion(t *testing.T) {
	// Arrange
	uc := biz.NewHealthUsecase("test-1.0.0")
	srv := newTestHTTPServer(service.NewHealthService(uc))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	rec := httptest.NewRecorder()

	// Act
	srv.ServeHTTP(rec, req)

	// Assert
	if rec.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusOK, rec.Body.String())
	}
	var body struct {
		Status  string `json:"status"`
		Version string `json:"version"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v (raw: %s)", err, rec.Body.String())
	}
	if body.Status != "ok" {
		t.Errorf("status = %q, want %q", body.Status, "ok")
	}
	if body.Version != "test-1.0.0" {
		t.Errorf("version = %q, want %q", body.Version, "test-1.0.0")
	}
}

func TestHTTPServer_ErrorEnvelope_includesTraceID(t *testing.T) {
	srv := newTestHTTPServer(service.NewHealthService(biz.NewHealthUsecase("test")))
	srv.Route("/api/v1").POST("/test-error", func(ctx khttp.Context) error {
		return bffkit.ValidationError([]bffkit.FieldError{{Field: "amount", Message: "amount is required"}})
	})

	req := httptest.NewRequest(http.MethodPost, "/api/v1/test-error", nil)
	req.Header.Set(bffkit.HeaderTraceID, "trace-http")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusUnprocessableEntity, rec.Body.String())
	}
	var body bffkit.ErrorEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.Error.Code != bffkit.CodeValidation {
		t.Fatalf("error code = %q, want %q", body.Error.Code, bffkit.CodeValidation)
	}
	if body.Error.TraceID != "trace-http" {
		t.Fatalf("traceId = %q, want trace-http", body.Error.TraceID)
	}
}

func TestHTTPServer_KratosError_usesUnifiedEnvelope(t *testing.T) {
	srv := newTestHTTPServer(service.NewHealthService(biz.NewHealthUsecase("test")))
	srv.Route("/api/v1").POST("/test-kratos-error", func(ctx khttp.Context) error {
		return errors.Unauthorized("AUTH", "login required")
	})

	req := httptest.NewRequest(http.MethodPost, "/api/v1/test-kratos-error", nil)
	req.Header.Set(bffkit.HeaderIdempotencyKey, "idem-kratos-error")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusUnauthorized, rec.Body.String())
	}
	var body bffkit.ErrorEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.Error.Code == "" {
		t.Fatal("expected stable error code")
	}
	if body.Error.Code != bffkit.CodeUnauthorized {
		t.Fatalf("error code = %q, want %q", body.Error.Code, bffkit.CodeUnauthorized)
	}
}

func TestHTTPServer_Idempotency_replaysFirstWriteResponse(t *testing.T) {
	srv := newTestHTTPServer(service.NewHealthService(biz.NewHealthUsecase("test")))
	calls := 0
	srv.Route("/api/v1").POST("/test-idempotency", func(ctx khttp.Context) error {
		calls++
		return ctx.JSON(http.StatusCreated, map[string]int{"call": calls})
	})

	first := httptest.NewRecorder()
	firstReq := httptest.NewRequest(http.MethodPost, "/api/v1/test-idempotency", nil)
	firstReq.Header.Set(bffkit.HeaderIdempotencyKey, "idem-http")
	srv.ServeHTTP(first, firstReq)

	second := httptest.NewRecorder()
	secondReq := httptest.NewRequest(http.MethodPost, "/api/v1/test-idempotency", nil)
	secondReq.Header.Set(bffkit.HeaderIdempotencyKey, "idem-http")
	srv.ServeHTTP(second, secondReq)

	if calls != 1 {
		t.Fatalf("handler calls = %d, want 1", calls)
	}
	if second.Body.String() != first.Body.String() {
		t.Fatalf("second body = %s, want replay %s", second.Body.String(), first.Body.String())
	}
}

func TestHTTPServer_CORSPreflight_doesNotRequireIdempotencyKey(t *testing.T) {
	authClient := &fakeApplicantAuthClient{}
	srv := NewHTTPServer(
		&conf.Server{CORS: conf.CORS{AllowedOrigins: []string{"http://localhost:3001"}}},
		service.NewHealthService(biz.NewHealthUsecase("test")),
		service.NewAuthService(biz.NewAuthUsecase(authClient)),
		fakeTokenValidator{applicantID: "applicant_001"},
		bffkit.NewMemoryIdempotencyStore(0),
		log.DefaultLogger,
	)

	req := httptest.NewRequest(http.MethodOptions, "/api/v1/auth/otp:send", nil)
	req.Header.Set("Origin", "http://localhost:3001")
	req.Header.Set("Access-Control-Request-Method", http.MethodPost)
	req.Header.Set("Access-Control-Request-Headers", "content-type,idempotency-key")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusNoContent, rec.Body.String())
	}
	if rec.Header().Get("Access-Control-Allow-Origin") != "http://localhost:3001" {
		t.Fatalf("allow origin = %q", rec.Header().Get("Access-Control-Allow-Origin"))
	}
}

func newTestHTTPServer(health *service.HealthService) *khttp.Server {
	authClient := &fakeApplicantAuthClient{}
	auth := service.NewAuthService(biz.NewAuthUsecase(authClient))
	return NewHTTPServer(
		&conf.Server{},
		health,
		auth,
		fakeTokenValidator{applicantID: "applicant_001"},
		bffkit.NewMemoryIdempotencyStore(0),
		log.DefaultLogger,
	)
}

func TestHTTPServer_ProtectedProbe_requiresBearerToken(t *testing.T) {
	srv := newTestHTTPServer(service.NewHealthService(biz.NewHealthUsecase("test")))

	req := httptest.NewRequest(http.MethodPost, "/api/v1/protected/session:probe", nil)
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusUnauthorized, rec.Body.String())
	}
}

func TestHTTPServer_ProtectedProbe_validTokenReturnsPrincipal(t *testing.T) {
	srv := newTestHTTPServer(service.NewHealthService(biz.NewHealthUsecase("test")))

	req := httptest.NewRequest(http.MethodPost, "/api/v1/protected/session:probe", nil)
	req.Header.Set("Authorization", "Bearer valid")
	req.Header.Set(bffkit.HeaderApplicantID, "attacker")
	req.Header.Set(bffkit.HeaderIdempotencyKey, "idem-probe")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusOK, rec.Body.String())
	}
	var body struct {
		ApplicantID string `json:"applicantId"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.ApplicantID != "applicant_001" {
		t.Fatalf("applicantId = %q, want applicant_001", body.ApplicantID)
	}
}

func TestHTTPServer_ProtectedPathMatcher_coversPricingAndLoanApplications(t *testing.T) {
	for _, path := range []string{
		"/api/v1/pricing/quotes",
		"/api/v1/loan-applications",
		"/api/v1/loan-applications/draft-1",
		"/api/v1/protected/session:probe",
	} {
		if !isProtectedPath(path) {
			t.Fatalf("isProtectedPath(%q) = false, want true", path)
		}
	}
	for _, path := range []string{
		"/api/v1/auth/otp:send",
		"/api/v1/auth/otp:verify",
		"/api/v1/auth/token:refresh",
		"/api/v1/health",
	} {
		if isProtectedPath(path) {
			t.Fatalf("isProtectedPath(%q) = true, want false", path)
		}
	}
}

func TestHTTPServer_AuthSendOtp_mapsRequestAndResponse(t *testing.T) {
	authClient := &fakeApplicantAuthClient{
		sendResult: biz.SendOtpResult{
			ChallengeID: "challenge-1",
			ExpiresIn:   5 * time.Minute,
			ResendAfter: time.Minute,
		},
	}
	srv := NewHTTPServer(
		&conf.Server{},
		service.NewHealthService(biz.NewHealthUsecase("test")),
		service.NewAuthService(biz.NewAuthUsecase(authClient)),
		fakeTokenValidator{applicantID: "applicant_001"},
		bffkit.NewMemoryIdempotencyStore(0),
		log.DefaultLogger,
	)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/otp:send", strings.NewReader(`{"countryCode":"+852","phone":"91234567"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(bffkit.HeaderIdempotencyKey, "idem-send")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusOK, rec.Body.String())
	}
	if authClient.sendCommand.CountryCode != "+852" || authClient.sendCommand.Phone != "91234567" || authClient.sendCommand.IdempotencyKey != "idem-send" {
		t.Fatalf("send command = %#v", authClient.sendCommand)
	}
	var body struct {
		ChallengeID    string `json:"challengeId"`
		ExpiresInSec   int32  `json:"expiresInSec"`
		ResendAfterSec int32  `json:"resendAfterSec"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.ChallengeID != "challenge-1" || body.ExpiresInSec != 300 || body.ResendAfterSec != 60 {
		t.Fatalf("body = %#v", body)
	}
}

func TestHTTPServer_AuthVerifyOtp_mapsExpiredError(t *testing.T) {
	authClient := &fakeApplicantAuthClient{
		verifyErr: &biz.AuthError{Code: biz.AuthCodeExpired, Message: "验证码已过期"},
	}
	srv := NewHTTPServer(
		&conf.Server{},
		service.NewHealthService(biz.NewHealthUsecase("test")),
		service.NewAuthService(biz.NewAuthUsecase(authClient)),
		fakeTokenValidator{applicantID: "applicant_001"},
		bffkit.NewMemoryIdempotencyStore(0),
		log.DefaultLogger,
	)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/otp:verify", strings.NewReader(`{"challengeId":"challenge-1","code":"123456"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(bffkit.HeaderIdempotencyKey, "idem-verify")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusUnprocessableEntity, rec.Body.String())
	}
	var body bffkit.ErrorEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.Error.Code != biz.AuthCodeExpired {
		t.Fatalf("error code = %q, want %q", body.Error.Code, biz.AuthCodeExpired)
	}
}

func TestHTTPServer_AuthSendOtp_mapsCooldownRetryAfter(t *testing.T) {
	authClient := &fakeApplicantAuthClient{
		sendErr: &biz.AuthError{Code: biz.AuthCodeCooldownActive, Message: "请稍后再试", RetryAfterSec: 42},
	}
	srv := NewHTTPServer(
		&conf.Server{},
		service.NewHealthService(biz.NewHealthUsecase("test")),
		service.NewAuthService(biz.NewAuthUsecase(authClient)),
		fakeTokenValidator{applicantID: "applicant_001"},
		bffkit.NewMemoryIdempotencyStore(0),
		log.DefaultLogger,
	)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/otp:send", strings.NewReader(`{"countryCode":"+852","phone":"91234567"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(bffkit.HeaderIdempotencyKey, "idem-cooldown")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusTooManyRequests, rec.Body.String())
	}
	if rec.Header().Get("Retry-After") != "42" {
		t.Fatalf("Retry-After = %q, want 42", rec.Header().Get("Retry-After"))
	}
	var body bffkit.ErrorEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.Error.Code != biz.AuthCodeCooldownActive || body.Error.RetryAfterSec != 42 {
		t.Fatalf("error = %#v", body.Error)
	}
}

func TestHTTPServer_AuthSendOtp_mapsConsulNoHealthyInstance(t *testing.T) {
	consul := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`[]`))
	}))
	defer consul.Close()

	authClient := data.NewApplicantAuthClient(&conf.Applicant{Consul: testConsulConfig(consul.URL)})
	srv := NewHTTPServer(
		&conf.Server{},
		service.NewHealthService(biz.NewHealthUsecase("test")),
		service.NewAuthService(biz.NewAuthUsecase(authClient)),
		fakeTokenValidator{applicantID: "applicant_001"},
		bffkit.NewMemoryIdempotencyStore(0),
		log.DefaultLogger,
	)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/otp:send", strings.NewReader(`{"countryCode":"+852","phone":"91234567"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(bffkit.HeaderIdempotencyKey, "idem-consul-empty")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusServiceUnavailable, rec.Body.String())
	}
	var body bffkit.ErrorEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.Error.Code != biz.AuthCodeApplicantUnavailable {
		t.Fatalf("error code = %q, want %q", body.Error.Code, biz.AuthCodeApplicantUnavailable)
	}
}

func TestHTTPServer_AuthSendOtp_requiresIdempotencyKey(t *testing.T) {
	srv := newTestHTTPServer(service.NewHealthService(biz.NewHealthUsecase("test")))

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/otp:send", strings.NewReader(`{"countryCode":"+852","phone":"91234567"}`))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusUnprocessableEntity, rec.Body.String())
	}
	var body bffkit.ErrorEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.Error.Field != bffkit.HeaderIdempotencyKey {
		t.Fatalf("error field = %q, want %q", body.Error.Field, bffkit.HeaderIdempotencyKey)
	}
}

type fakeApplicantAuthClient struct {
	sendCommand biz.SendOtpCommand
	sendResult  biz.SendOtpResult
	sendErr     error
	verifyErr   error
}

type fakeTokenValidator struct {
	applicantID string
}

func (v fakeTokenValidator) ValidateAccessToken(context.Context, string) (bffkit.Principal, error) {
	return bffkit.Principal{ApplicantID: v.applicantID, TokenID: "token-1", ExpiresAt: time.Now().Add(time.Hour)}, nil
}

func (f *fakeApplicantAuthClient) SendOtp(_ context.Context, command biz.SendOtpCommand) (biz.SendOtpResult, error) {
	f.sendCommand = command
	return f.sendResult, f.sendErr
}

func (f *fakeApplicantAuthClient) VerifyOtp(_ context.Context, _ biz.VerifyOtpCommand) (biz.VerifyOtpResult, error) {
	return biz.VerifyOtpResult{}, f.verifyErr
}

func (f *fakeApplicantAuthClient) RefreshToken(context.Context, biz.RefreshTokenCommand) (biz.RefreshTokenResult, error) {
	return biz.RefreshTokenResult{AccessToken: "access", ExpiresIn: time.Hour}, nil
}

func testConsulConfig(raw string) conf.Consul {
	parts := strings.SplitN(raw, "://", 2)
	return conf.Consul{Scheme: parts[0], Address: parts[1], ServiceName: "applicant-api"}
}
