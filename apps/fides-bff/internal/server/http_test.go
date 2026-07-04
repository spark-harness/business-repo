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
	"github.com/go-kratos/kratos/v2/transport"
	khttp "github.com/go-kratos/kratos/v2/transport/http"
	fidesbffv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/fides-bff/v1"
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
		newFakePricingService(),
		newFakeOriginationService(),
		newFakeIdentityProfileService(),
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

func TestHTTPServer_CORSPreflight_allowsIdentityProfilePut(t *testing.T) {
	authClient := &fakeApplicantAuthClient{}
	srv := NewHTTPServer(
		&conf.Server{CORS: conf.CORS{AllowedOrigins: []string{"http://localhost:3001"}}},
		service.NewHealthService(biz.NewHealthUsecase("test")),
		service.NewAuthService(biz.NewAuthUsecase(authClient)),
		newFakePricingService(),
		newFakeOriginationService(),
		newFakeIdentityProfileService(),
		fakeTokenValidator{applicantID: "applicant_001"},
		bffkit.NewMemoryIdempotencyStore(0),
		log.DefaultLogger,
	)

	req := httptest.NewRequest(http.MethodOptions, "/api/v1/me/identity-profile", nil)
	req.Header.Set("Origin", "http://localhost:3001")
	req.Header.Set("Access-Control-Request-Method", http.MethodPut)
	req.Header.Set("Access-Control-Request-Headers", "authorization,content-type,idempotency-key")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusNoContent, rec.Body.String())
	}
	if !strings.Contains(rec.Header().Get("Access-Control-Allow-Methods"), http.MethodPut) {
		t.Fatalf("allow methods = %q, want %s", rec.Header().Get("Access-Control-Allow-Methods"), http.MethodPut)
	}
}

func newTestHTTPServer(health *service.HealthService) *khttp.Server {
	authClient := &fakeApplicantAuthClient{}
	auth := service.NewAuthService(biz.NewAuthUsecase(authClient))
	pricing := service.NewPricingService(biz.NewPricingUsecase(&fakeQuoteClient{}))
	return NewHTTPServer(
		&conf.Server{},
		health,
		auth,
		pricing,
		newFakeOriginationService(),
		newFakeIdentityProfileService(),
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

func TestHTTPServer_PricingQuote_requiresBearerToken(t *testing.T) {
	quoteClient := &fakeQuoteClient{}
	srv := newTestHTTPServerWithPricing(quoteClient, fakeTokenValidator{applicantID: "applicant_001"})

	req := pricingQuoteRequest()
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusUnauthorized, rec.Body.String())
	}
	if quoteClient.calls != 0 {
		t.Fatalf("quote-api calls = %d, want 0", quoteClient.calls)
	}
}

func TestHTTPServer_PricingQuote_rejectsInvalidToken(t *testing.T) {
	quoteClient := &fakeQuoteClient{}
	srv := newTestHTTPServerWithPricing(quoteClient, fakeTokenValidator{err: bffkit.ErrInvalidAccessToken})

	req := pricingQuoteRequest()
	req.Header.Set("Authorization", "Bearer invalid")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusUnauthorized, rec.Body.String())
	}
	if quoteClient.calls != 0 {
		t.Fatalf("quote-api calls = %d, want 0", quoteClient.calls)
	}
}

func TestHTTPServer_PricingQuote_callsQuoteAPIWithPrincipal(t *testing.T) {
	quoteClient := &fakeQuoteClient{
		result: biz.QuoteResult{
			QuoteID:       "quote_123",
			Monthly:       "8560.75",
			APR:           "0.0520",
			TotalInterest: "2729.00",
			TotalPayable:  "102729.00",
			ValidUntil:    "2026-06-28T03:00:00Z",
		},
	}
	srv := newTestHTTPServerWithPricing(quoteClient, fakeTokenValidator{applicantID: "applicant_001"})

	req := pricingQuoteRequest()
	req.Header.Set("Authorization", "Bearer valid")
	req.Header.Set(bffkit.HeaderApplicantID, "attacker")
	req.Header.Set(bffkit.HeaderTraceParent, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
	req.Header.Set(bffkit.HeaderTraceState, "vendor=state")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusOK, rec.Body.String())
	}
	if quoteClient.calls != 1 {
		t.Fatalf("quote-api calls = %d, want 1", quoteClient.calls)
	}
	if quoteClient.command.ApplicantID != "applicant_001" {
		t.Fatalf("applicantId = %q, want applicant_001", quoteClient.command.ApplicantID)
	}
	if quoteClient.operation != fidesbffv1pb.OperationFidesBffPricingServiceCreateQuote {
		t.Fatalf("operation = %q, want %q", quoteClient.operation, fidesbffv1pb.OperationFidesBffPricingServiceCreateQuote)
	}
	var body struct {
		QuoteID       string `json:"quoteId"`
		Monthly       string `json:"monthly"`
		APR           string `json:"apr"`
		TotalInterest string `json:"totalInterest"`
		TotalPayable  string `json:"totalPayable"`
		ValidUntil    string `json:"validUntil"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.QuoteID != "quote_123" || body.Monthly != "8560.75" || body.APR != "0.0520" || body.TotalInterest != "2729.00" || body.TotalPayable != "102729.00" || body.ValidUntil == "" {
		t.Fatalf("body = %#v", body)
	}
}

func TestHTTPServer_PricingQuote_mapsAmountOutOfRange(t *testing.T) {
	quoteClient := &fakeQuoteClient{err: &biz.PricingError{Code: biz.PricingCodeAmountOutOfRange, Message: "amount out of range"}}
	srv := newTestHTTPServerWithPricing(quoteClient, fakeTokenValidator{applicantID: "applicant_001"})

	req := pricingQuoteRequest()
	req.Header.Set("Authorization", "Bearer valid")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusUnprocessableEntity, rec.Body.String())
	}
	assertErrorCode(t, rec.Body.Bytes(), biz.PricingCodeAmountOutOfRange)
}

func TestHTTPServer_PricingQuote_mapsQuoteUnavailable(t *testing.T) {
	quoteClient := &fakeQuoteClient{err: &biz.PricingError{Code: biz.PricingCodeQuoteUnavailable, Message: "quote-api is unavailable"}}
	srv := newTestHTTPServerWithPricing(quoteClient, fakeTokenValidator{applicantID: "applicant_001"})

	req := pricingQuoteRequest()
	req.Header.Set("Authorization", "Bearer valid")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusBadGateway, rec.Body.String())
	}
	assertErrorCode(t, rec.Body.Bytes(), biz.PricingCodeQuoteUnavailable)
}

func TestHTTPServer_LoanApplication_requiresBearerToken(t *testing.T) {
	originationClient := &fakeOriginationClient{}
	srv := newTestHTTPServerWithOrigination(originationClient, fakeTokenValidator{applicantID: "applicant_001"})

	req := loanApplicationCreateRequest()
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusUnauthorized, rec.Body.String())
	}
	if originationClient.calls != 0 {
		t.Fatalf("origination-api calls = %d, want 0", originationClient.calls)
	}
}

func TestHTTPServer_LoanApplication_rejectsInvalidToken(t *testing.T) {
	originationClient := &fakeOriginationClient{}
	srv := newTestHTTPServerWithOrigination(originationClient, fakeTokenValidator{err: bffkit.ErrInvalidAccessToken})

	req := loanApplicationCreateRequest()
	req.Header.Set("Authorization", "Bearer invalid")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusUnauthorized, rec.Body.String())
	}
	if originationClient.calls != 0 {
		t.Fatalf("origination-api calls = %d, want 0", originationClient.calls)
	}
}

func TestHTTPServer_LoanApplicationCreate_callsOriginationWithPrincipalTraceAndIdempotency(t *testing.T) {
	originationClient := &fakeOriginationClient{
		summary: biz.LoanApplicationSummary{
			ApplicationID: "app_123",
			Status:        "draft",
			CurrentStep:   "loan_request",
		},
	}
	srv := newTestHTTPServerWithOrigination(originationClient, fakeTokenValidator{applicantID: "applicant_001"})

	req := loanApplicationCreateRequest()
	req.Header.Set("Authorization", "Bearer valid")
	req.Header.Set(bffkit.HeaderApplicantID, "attacker")
	req.Header.Set(bffkit.HeaderTraceParent, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
	req.Header.Set(bffkit.HeaderTraceState, "vendor=state")
	req.Header.Set(bffkit.HeaderIdempotencyKey, "idem-create")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusOK, rec.Body.String())
	}
	if originationClient.calls != 1 {
		t.Fatalf("origination-api calls = %d, want 1", originationClient.calls)
	}
	if originationClient.createCommand.ApplicantID != "applicant_001" {
		t.Fatalf("applicantId = %q, want applicant_001", originationClient.createCommand.ApplicantID)
	}
	if originationClient.createCommand.IdempotencyKey != "idem-create" {
		t.Fatalf("idempotency key = %q, want idem-create", originationClient.createCommand.IdempotencyKey)
	}
	if originationClient.createCommand.TraceParent == "" {
		t.Fatal("traceparent was not propagated")
	}
	if originationClient.createCommand.TraceState != "vendor=state" {
		t.Fatalf("tracestate = %q, want vendor=state", originationClient.createCommand.TraceState)
	}
	if originationClient.createOperation != fidesbffv1pb.OperationFidesBffLoanApplicationServiceCreateLoanApplication {
		t.Fatalf("operation = %q, want %q", originationClient.createOperation, fidesbffv1pb.OperationFidesBffLoanApplicationServiceCreateLoanApplication)
	}
	var body struct {
		ApplicationID string `json:"applicationId"`
		Status        string `json:"status"`
		CurrentStep   string `json:"currentStep"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.ApplicationID != "app_123" || body.Status != "draft" || body.CurrentStep != "loan_request" {
		t.Fatalf("body = %#v", body)
	}
}

func TestHTTPServer_LoanApplicationGet_returnsDetail(t *testing.T) {
	originationClient := &fakeOriginationClient{
		detail: biz.LoanApplicationDetail{
			ApplicationID: "app_123",
			Loan:          biz.LoanTerms{Amount: "100000.00", Term: 12, Purpose: "debt_consolidation"},
			AcceptedQuote: biz.AcceptedQuote{
				QuoteID:       "quote_123",
				Monthly:       "8560.75",
				APR:           "0.0520",
				TotalInterest: "2729.00",
				TotalPayable:  "102729.00",
				ValidUntil:    "2026-06-28T03:00:00Z",
			},
			Status:      "draft",
			CurrentStep: "loan_request",
		},
	}
	srv := newTestHTTPServerWithOrigination(originationClient, fakeTokenValidator{applicantID: "applicant_001"})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/loan-applications/app_123", nil)
	req.Header.Set("Authorization", "Bearer valid")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusOK, rec.Body.String())
	}
	if originationClient.getCommand.ApplicationID != "app_123" {
		t.Fatalf("applicationId = %q, want app_123", originationClient.getCommand.ApplicationID)
	}
	if originationClient.getOperation != fidesbffv1pb.OperationFidesBffLoanApplicationServiceGetLoanApplication {
		t.Fatalf("operation = %q, want %q", originationClient.getOperation, fidesbffv1pb.OperationFidesBffLoanApplicationServiceGetLoanApplication)
	}
	var body struct {
		ApplicationID string `json:"applicationId"`
		Loan          struct {
			Amount  string `json:"amount"`
			Term    int    `json:"term"`
			Purpose string `json:"purpose"`
		} `json:"loan"`
		AcceptedQuote struct {
			QuoteID string `json:"quoteId"`
			Monthly string `json:"monthly"`
		} `json:"acceptedQuote"`
		Status      string `json:"status"`
		CurrentStep string `json:"currentStep"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.ApplicationID != "app_123" || body.Loan.Amount != "100000.00" || body.AcceptedQuote.QuoteID != "quote_123" || body.CurrentStep != "loan_request" {
		t.Fatalf("body = %#v", body)
	}
}

func TestHTTPServer_LoanApplicationPatch_propagatesIdempotency(t *testing.T) {
	originationClient := &fakeOriginationClient{
		summary: biz.LoanApplicationSummary{
			ApplicationID: "app_123",
			Status:        "draft",
			CurrentStep:   "loan_request",
		},
	}
	srv := newTestHTTPServerWithOrigination(originationClient, fakeTokenValidator{applicantID: "applicant_001"})

	req := loanApplicationPatchRequest("app_123")
	req.Header.Set("Authorization", "Bearer valid")
	req.Header.Set(bffkit.HeaderIdempotencyKey, "idem-patch")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusOK, rec.Body.String())
	}
	if originationClient.patchCommand.ApplicationID != "app_123" {
		t.Fatalf("applicationId = %q, want app_123", originationClient.patchCommand.ApplicationID)
	}
	if originationClient.patchCommand.IdempotencyKey != "idem-patch" {
		t.Fatalf("idempotency key = %q, want idem-patch", originationClient.patchCommand.IdempotencyKey)
	}
	if originationClient.patchOperation != fidesbffv1pb.OperationFidesBffLoanApplicationServiceUpdateLoanApplication {
		t.Fatalf("operation = %q, want %q", originationClient.patchOperation, fidesbffv1pb.OperationFidesBffLoanApplicationServiceUpdateLoanApplication)
	}
}

func TestHTTPServer_LoanApplication_mapsOriginationErrors(t *testing.T) {
	tests := []struct {
		name       string
		code       string
		wantStatus int
	}{
		{"idempotency required", biz.OriginationCodeIdempotencyKeyRequired, http.StatusBadRequest},
		{"forbidden", biz.OriginationCodeForbidden, http.StatusForbidden},
		{"not found", biz.OriginationCodeNotFound, http.StatusNotFound},
		{"quote expired", biz.OriginationCodeQuoteExpired, http.StatusGone},
		{"amount out of range", biz.OriginationCodeAmountOutOfRange, http.StatusUnprocessableEntity},
		{"validation", biz.OriginationCodeValidation, http.StatusUnprocessableEntity},
		{"unavailable", biz.OriginationCodeUnavailable, http.StatusBadGateway},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			originationClient := &fakeOriginationClient{err: &biz.OriginationError{Code: tt.code, Message: tt.code}}
			srv := newTestHTTPServerWithOrigination(originationClient, fakeTokenValidator{applicantID: "applicant_001"})

			req := loanApplicationCreateRequest()
			req.Header.Set("Authorization", "Bearer valid")
			req.Header.Set(bffkit.HeaderIdempotencyKey, "idem-error")
			rec := httptest.NewRecorder()

			srv.ServeHTTP(rec, req)

			if rec.Code != tt.wantStatus {
				t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, tt.wantStatus, rec.Body.String())
			}
			assertErrorCode(t, rec.Body.Bytes(), tt.code)
		})
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
		newFakePricingService(),
		newFakeOriginationService(),
		newFakeIdentityProfileService(),
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
		newFakePricingService(),
		newFakeOriginationService(),
		newFakeIdentityProfileService(),
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
		newFakePricingService(),
		newFakeOriginationService(),
		newFakeIdentityProfileService(),
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
		newFakePricingService(),
		newFakeOriginationService(),
		newFakeIdentityProfileService(),
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

func TestHTTPServer_IdentityProfilePut_savesProfileAndAdvancesStep(t *testing.T) {
	applicant := &fakeApplicantProfileClient{
		upsertProfile: biz.IdentityProfile{
			HKIDBody:       "A123456",
			HKIDCheckDigit: "3",
			FirstName:      "Ada",
			LastName:       "Lovelace",
			ChineseName:    "Test Name",
			Nationality:    "hong_kong",
			DateOfBirth:    "1990-01-15",
		},
	}
	draft := &fakeOriginationDraftClient{
		result: biz.AdvanceApplicationStepResult{ApplicationID: "app_001", CurrentStep: "identity_information"},
	}
	identityProfile := service.NewIdentityProfileService(biz.NewIdentityProfileUsecase(applicant, draft))
	srv := NewHTTPServer(
		&conf.Server{},
		service.NewHealthService(biz.NewHealthUsecase("test")),
		service.NewAuthService(biz.NewAuthUsecase(&fakeApplicantAuthClient{})),
		newFakePricingService(),
		newFakeOriginationService(),
		identityProfile,
		fakeTokenValidator{applicantID: "applicant_001"},
		bffkit.NewMemoryIdempotencyStore(0),
		log.DefaultLogger,
	)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/me/identity-profile", strings.NewReader(`{"applicationId":"app_001","profile":{"hkidBody":"A123456","hkidCheckDigit":"3","firstName":"Ada","lastName":"Lovelace","chineseName":"Test Name","nationality":2,"dateOfBirth":"1990-01-15"}}`))
	req.Header.Set("Authorization", "Bearer valid")
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(bffkit.HeaderIdempotencyKey, "idem-identity-profile")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusOK, rec.Body.String())
	}
	if applicant.upsertCommand.ApplicantID != "applicant_001" || applicant.upsertCommand.ApplicationID != "app_001" {
		t.Fatalf("upsert command = %#v", applicant.upsertCommand)
	}
	if applicant.upsertCommand.Profile.Nationality != "hong_kong" {
		t.Fatalf("nationality = %q, want hong_kong", applicant.upsertCommand.Profile.Nationality)
	}
	if applicant.upsertOperation != fidesbffv1pb.OperationFidesBffIdentityProfileServiceUpsertIdentityProfile {
		t.Fatalf("operation = %q, want %q", applicant.upsertOperation, fidesbffv1pb.OperationFidesBffIdentityProfileServiceUpsertIdentityProfile)
	}
	if draft.command.ApplicantID != "applicant_001" || draft.command.ApplicationID != "app_001" {
		t.Fatalf("advance command = %#v", draft.command)
	}
	var body struct {
		CurrentStep string `json:"currentStep"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.CurrentStep != "identity_information" {
		t.Fatalf("currentStep = %q, want identity_information", body.CurrentStep)
	}
}

func TestHTTPServer_IdentityProfileGet_whenEmpty_shouldReturnEmptyResponse(t *testing.T) {
	applicant := &fakeApplicantProfileClient{getResult: biz.GetIdentityProfileResult{Empty: true}}
	identityProfile := service.NewIdentityProfileService(biz.NewIdentityProfileUsecase(applicant, &fakeOriginationDraftClient{}))
	srv := NewHTTPServer(
		&conf.Server{},
		service.NewHealthService(biz.NewHealthUsecase("test")),
		service.NewAuthService(biz.NewAuthUsecase(&fakeApplicantAuthClient{})),
		newFakePricingService(),
		newFakeOriginationService(),
		identityProfile,
		fakeTokenValidator{applicantID: "applicant_001"},
		bffkit.NewMemoryIdempotencyStore(0),
		log.DefaultLogger,
	)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/me/identity-profile?applicationId=app_001", nil)
	req.Header.Set("Authorization", "Bearer valid")
	rec := httptest.NewRecorder()

	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusOK, rec.Body.String())
	}
	if applicant.getCommand.ApplicantID != "applicant_001" || applicant.getCommand.ApplicationID != "app_001" {
		t.Fatalf("get command = %#v", applicant.getCommand)
	}
	if applicant.getOperation != fidesbffv1pb.OperationFidesBffIdentityProfileServiceGetIdentityProfile {
		t.Fatalf("operation = %q, want %q", applicant.getOperation, fidesbffv1pb.OperationFidesBffIdentityProfileServiceGetIdentityProfile)
	}
	var body struct {
		Empty bool `json:"empty"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if !body.Empty {
		t.Fatalf("empty = false, want true")
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
	err         error
}

func (v fakeTokenValidator) ValidateAccessToken(context.Context, string) (bffkit.Principal, error) {
	if v.err != nil {
		return bffkit.Principal{}, v.err
	}
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

func newFakePricingService() *service.PricingService {
	return service.NewPricingService(biz.NewPricingUsecase(&fakeQuoteClient{}))
}

func newFakeOriginationService() *service.OriginationService {
	return service.NewOriginationService(biz.NewOriginationUsecase(&fakeOriginationClient{}))
}

func newFakeIdentityProfileService() *service.IdentityProfileService {
	return service.NewIdentityProfileService(biz.NewIdentityProfileUsecase(
		&fakeApplicantProfileClient{},
		&fakeOriginationDraftClient{},
	))
}

func newTestHTTPServerWithPricing(quoteClient biz.QuoteClient, tokenValidator bffkit.TokenValidator) *khttp.Server {
	authClient := &fakeApplicantAuthClient{}
	auth := service.NewAuthService(biz.NewAuthUsecase(authClient))
	pricing := service.NewPricingService(biz.NewPricingUsecase(quoteClient))
	origination := newFakeOriginationService()
	return NewHTTPServer(
		&conf.Server{},
		service.NewHealthService(biz.NewHealthUsecase("test")),
		auth,
		pricing,
		origination,
		newFakeIdentityProfileService(),
		tokenValidator,
		bffkit.NewMemoryIdempotencyStore(0),
		log.DefaultLogger,
	)
}

func newTestHTTPServerWithOrigination(originationClient biz.OriginationClient, tokenValidator bffkit.TokenValidator) *khttp.Server {
	authClient := &fakeApplicantAuthClient{}
	auth := service.NewAuthService(biz.NewAuthUsecase(authClient))
	pricing := newFakePricingService()
	origination := service.NewOriginationService(biz.NewOriginationUsecase(originationClient))
	return NewHTTPServer(
		&conf.Server{},
		service.NewHealthService(biz.NewHealthUsecase("test")),
		auth,
		pricing,
		origination,
		newFakeIdentityProfileService(),
		tokenValidator,
		bffkit.NewMemoryIdempotencyStore(0),
		log.DefaultLogger,
	)
}

func pricingQuoteRequest() *http.Request {
	req := httptest.NewRequest(http.MethodPost, "/api/v1/pricing/quotes", strings.NewReader(`{"productCode":"PIL","amount":"100000.00","term":12,"purpose":"debt_consolidation"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(bffkit.HeaderIdempotencyKey, "idem-pricing-"+time.Now().Format("150405.000000000"))
	return req
}

func loanApplicationCreateRequest() *http.Request {
	req := httptest.NewRequest(http.MethodPost, "/api/v1/loan-applications", strings.NewReader(`{"productCode":"PIL","loan":{"amount":"100000.00","term":12,"purpose":"debt_consolidation"},"quoteId":"quote_123"}`))
	req.Header.Set("Content-Type", "application/json")
	return req
}

func loanApplicationPatchRequest(applicationID string) *http.Request {
	req := httptest.NewRequest(http.MethodPatch, "/api/v1/loan-applications/"+applicationID, strings.NewReader(`{"loan":{"amount":"120000.00","term":24,"purpose":"debt_consolidation"},"quoteId":"quote_456"}`))
	req.Header.Set("Content-Type", "application/json")
	return req
}

func assertErrorCode(t *testing.T, raw []byte, want string) {
	t.Helper()
	var body bffkit.ErrorEnvelope
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if body.Error.Code != want {
		t.Fatalf("error code = %q, want %q", body.Error.Code, want)
	}
}

type fakeQuoteClient struct {
	calls     int
	command   biz.CreateQuoteCommand
	result    biz.QuoteResult
	err       error
	operation string
}

func (f *fakeQuoteClient) CreateQuote(ctx context.Context, command biz.CreateQuoteCommand) (biz.QuoteResult, error) {
	f.calls++
	f.command = command
	f.operation = operationFromContext(ctx)
	return f.result, f.err
}

type fakeOriginationClient struct {
	calls           int
	createCommand   biz.CreateLoanApplicationCommand
	getCommand      biz.GetLoanApplicationCommand
	patchCommand    biz.PatchLoanApplicationCommand
	createOperation string
	getOperation    string
	patchOperation  string
	summary         biz.LoanApplicationSummary
	detail          biz.LoanApplicationDetail
	err             error
}

func (f *fakeOriginationClient) CreateLoanApplication(ctx context.Context, command biz.CreateLoanApplicationCommand) (biz.LoanApplicationSummary, error) {
	f.calls++
	f.createCommand = command
	f.createOperation = operationFromContext(ctx)
	return f.summary, f.err
}

func (f *fakeOriginationClient) GetLoanApplication(ctx context.Context, command biz.GetLoanApplicationCommand) (biz.LoanApplicationDetail, error) {
	f.calls++
	f.getCommand = command
	f.getOperation = operationFromContext(ctx)
	return f.detail, f.err
}

func (f *fakeOriginationClient) PatchLoanApplication(ctx context.Context, command biz.PatchLoanApplicationCommand) (biz.LoanApplicationSummary, error) {
	f.calls++
	f.patchCommand = command
	f.patchOperation = operationFromContext(ctx)
	return f.summary, f.err
}

type fakeApplicantProfileClient struct {
	upsertCommand   biz.UpsertIdentityProfileCommand
	getCommand      biz.GetIdentityProfileCommand
	upsertOperation string
	getOperation    string
	upsertProfile   biz.IdentityProfile
	getResult       biz.GetIdentityProfileResult
	err             error
}

func (f *fakeApplicantProfileClient) UpsertIdentityProfile(ctx context.Context, command biz.UpsertIdentityProfileCommand) (biz.IdentityProfile, error) {
	f.upsertCommand = command
	f.upsertOperation = operationFromContext(ctx)
	return f.upsertProfile, f.err
}

func (f *fakeApplicantProfileClient) GetIdentityProfile(ctx context.Context, command biz.GetIdentityProfileCommand) (biz.GetIdentityProfileResult, error) {
	f.getCommand = command
	f.getOperation = operationFromContext(ctx)
	return f.getResult, f.err
}

func operationFromContext(ctx context.Context) string {
	tr, ok := transport.FromServerContext(ctx)
	if !ok {
		return ""
	}
	return tr.Operation()
}

type fakeOriginationDraftClient struct {
	command biz.AdvanceApplicationStepCommand
	result  biz.AdvanceApplicationStepResult
	err     error
}

func (f *fakeOriginationDraftClient) AdvanceApplicationStep(_ context.Context, command biz.AdvanceApplicationStepCommand) (biz.AdvanceApplicationStepResult, error) {
	f.command = command
	return f.result, f.err
}
