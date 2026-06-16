package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

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
	srv := NewHTTPServer(&conf.Server{}, service.NewHealthService(uc))

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
