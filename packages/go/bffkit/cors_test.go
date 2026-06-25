package bffkit

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCORSFilter_handlesAllowedPreflight(t *testing.T) {
	calls := 0
	handler := CORSFilter(CORSConfig{AllowedOrigins: []string{"http://localhost:3001"}, MaxAgeSec: 600})(
		http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			calls++
			w.WriteHeader(http.StatusOK)
		}),
	)

	req := httptest.NewRequest(http.MethodOptions, "/api/v1/auth/otp:send", nil)
	req.Header.Set("Origin", "http://localhost:3001")
	req.Header.Set("Access-Control-Request-Method", http.MethodPost)
	req.Header.Set("Access-Control-Request-Headers", "content-type,idempotency-key,traceparent,x-trace-id")
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	if calls != 0 {
		t.Fatalf("handler calls = %d, want 0", calls)
	}
	if rec.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusNoContent)
	}
	if rec.Header().Get("Access-Control-Allow-Origin") != "http://localhost:3001" {
		t.Fatalf("allow origin = %q", rec.Header().Get("Access-Control-Allow-Origin"))
	}
	if rec.Header().Get("Access-Control-Max-Age") != "600" {
		t.Fatalf("max age = %q, want 600", rec.Header().Get("Access-Control-Max-Age"))
	}
	if got, want := rec.Header().Get("Access-Control-Allow-Headers"), "content-type,idempotency-key,traceparent,x-trace-id"; got != want {
		t.Fatalf("allow headers = %q, want %q", got, want)
	}
}

func TestCORSFilter_defaultsAllowTraceHeaders(t *testing.T) {
	handler := CORSFilter(CORSConfig{AllowedOrigins: []string{"http://localhost:3001"}})(
		http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusOK)
		}),
	)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	req.Header.Set("Origin", "http://localhost:3001")
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	allowHeaders := strings.ToLower(rec.Header().Get("Access-Control-Allow-Headers"))
	for _, header := range []string{"traceparent", "tracestate", "baggage", "idempotency-key", "x-trace-id"} {
		if !strings.Contains(allowHeaders, strings.ToLower(header)) {
			t.Fatalf("allow headers = %q, want %s", allowHeaders, header)
		}
	}
}

func TestCORSFilter_addsHeadersForAllowedActualRequest(t *testing.T) {
	handler := CORSFilter(CORSConfig{AllowedOrigins: []string{"http://localhost:3001"}})(
		http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusCreated)
		}),
	)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/otp:send", nil)
	req.Header.Set("Origin", "http://localhost:3001")
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusCreated)
	}
	if rec.Header().Get("Access-Control-Allow-Origin") != "http://localhost:3001" {
		t.Fatalf("allow origin = %q", rec.Header().Get("Access-Control-Allow-Origin"))
	}
	if rec.Header().Get("Access-Control-Expose-Headers") == "" {
		t.Fatal("expected exposed headers")
	}
}

func TestCORSFilter_skipsDisallowedOrigin(t *testing.T) {
	handler := CORSFilter(CORSConfig{AllowedOrigins: []string{"http://localhost:3001"}})(
		http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusOK)
		}),
	)

	req := httptest.NewRequest(http.MethodOptions, "/api/v1/auth/otp:send", nil)
	req.Header.Set("Origin", "http://evil.example")
	req.Header.Set("Access-Control-Request-Method", http.MethodPost)
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	if rec.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Fatalf("allow origin = %q, want empty", rec.Header().Get("Access-Control-Allow-Origin"))
	}
}
