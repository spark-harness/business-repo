package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/go-kratos/kratos/v2/errors"
	"github.com/go-kratos/kratos/v2/log"
	khttp "github.com/go-kratos/kratos/v2/transport/http"
	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/conf"
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

func newTestHTTPServer(health *service.HealthService) *khttp.Server {
	return NewHTTPServer(&conf.Server{}, health, bffkit.NewMemoryIdempotencyStore(0), log.DefaultLogger)
}
