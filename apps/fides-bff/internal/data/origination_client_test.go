package data

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/conf"
)

func TestOriginationClient_CreateCallsOriginationAPIWithApplicantTraceAndIdempotency(t *testing.T) {
	var gotApplicantID string
	var gotTraceParent string
	var gotTraceState string
	var gotIdempotencyKey string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/loan-applications" {
			t.Fatalf("path = %q", r.URL.Path)
		}
		gotApplicantID = r.Header.Get(bffkit.HeaderApplicantID)
		gotTraceParent = r.Header.Get(bffkit.HeaderTraceParent)
		gotTraceState = r.Header.Get(bffkit.HeaderTraceState)
		gotIdempotencyKey = r.Header.Get(bffkit.HeaderIdempotencyKey)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"applicationId":"app_123","status":"draft","currentStep":"loan_request"}`))
	}))
	defer server.Close()

	client := NewOriginationClient(&conf.Origination{HTTP: conf.OriginationHTTP{BaseURL: server.URL, Timeout: "1s"}})
	result, err := client.CreateLoanApplication(context.Background(), biz.CreateLoanApplicationCommand{
		ApplicantID:    "applicant_001",
		IdempotencyKey: "idem-create",
		TraceParent:    "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
		TraceState:     "vendor=state",
		RawRequest:     json.RawMessage(`{"productCode":"PIL"}`),
	})
	if err != nil {
		t.Fatalf("CreateLoanApplication() error = %v", err)
	}
	if result.ApplicationID != "app_123" || result.Status != "draft" || result.CurrentStep != "loan_request" {
		t.Fatalf("result = %#v", result)
	}
	if gotApplicantID != "applicant_001" {
		t.Fatalf("x-applicant-id = %q, want applicant_001", gotApplicantID)
	}
	if gotTraceParent == "" || gotTraceState != "vendor=state" {
		t.Fatalf("trace headers = %q / %q", gotTraceParent, gotTraceState)
	}
	if gotIdempotencyKey != "idem-create" {
		t.Fatalf("Idempotency-Key = %q, want idem-create", gotIdempotencyKey)
	}
}

func TestOriginationClient_GetReturnsDetail(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/loan-applications/app_123" {
			t.Fatalf("path = %q", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"applicationId":"app_123","loan":{"amount":"100000.00","term":12,"purpose":"debt_consolidation"},"acceptedQuote":{"quoteId":"quote_123","monthly":"8560.75","apr":"0.0520","totalInterest":"2729.00","totalPayable":"102729.00","validUntil":"2026-06-28T03:00:00Z"},"status":"draft","currentStep":"loan_request"}`))
	}))
	defer server.Close()

	client := NewOriginationClient(&conf.Origination{HTTP: conf.OriginationHTTP{BaseURL: server.URL, Timeout: "1s"}})
	result, err := client.GetLoanApplication(context.Background(), biz.GetLoanApplicationCommand{
		ApplicantID:   "applicant_001",
		ApplicationID: "app_123",
	})
	if err != nil {
		t.Fatalf("GetLoanApplication() error = %v", err)
	}
	if result.ApplicationID != "app_123" || result.Loan.Amount != "100000.00" || result.AcceptedQuote.QuoteID != "quote_123" {
		t.Fatalf("result = %#v", result)
	}
}

func TestOriginationClient_MapsOriginationErrors(t *testing.T) {
	tests := []struct {
		name       string
		statusCode int
		body       string
		wantCode   string
	}{
		{"idempotency required", http.StatusBadRequest, `{"error":"idempotency_key_required"}`, biz.OriginationCodeIdempotencyKeyRequired},
		{"forbidden", http.StatusForbidden, `{"error":"forbidden"}`, biz.OriginationCodeForbidden},
		{"not found", http.StatusNotFound, `{"error":"not_found"}`, biz.OriginationCodeNotFound},
		{"quote expired", http.StatusGone, `{"error":"quote_expired"}`, biz.OriginationCodeQuoteExpired},
		{"amount out of range", http.StatusUnprocessableEntity, `{"error":"amount_out_of_range"}`, biz.OriginationCodeAmountOutOfRange},
		{"validation", http.StatusUnprocessableEntity, `{"error":"validation_error"}`, biz.OriginationCodeValidation},
		{"unavailable", http.StatusInternalServerError, `{}`, biz.OriginationCodeUnavailable},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(tt.statusCode)
				_, _ = w.Write([]byte(tt.body))
			}))
			defer server.Close()

			client := NewOriginationClient(&conf.Origination{HTTP: conf.OriginationHTTP{BaseURL: server.URL, Timeout: "1s"}})
			_, err := client.CreateLoanApplication(context.Background(), biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", RawRequest: json.RawMessage(`{}`)})
			originationErr, ok := err.(*biz.OriginationError)
			if !ok {
				t.Fatalf("err = %#v, want OriginationError", err)
			}
			if originationErr.Code != tt.wantCode {
				t.Fatalf("code = %q, want %q", originationErr.Code, tt.wantCode)
			}
		})
	}
}

func TestOriginationConsulResolver_ResolveReturnsHTTPServiceURL(t *testing.T) {
	consul := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/health/service/origination-api" {
			t.Fatalf("path = %q", r.URL.Path)
		}
		if r.URL.Query().Get("passing") != "true" {
			t.Fatalf("passing = %q", r.URL.Query().Get("passing"))
		}
		_, _ = w.Write([]byte(`[{"Node":{"Address":"10.0.0.10"},"Service":{"Address":"origination-api.lendora-sta-origination-api.svc.cluster.local","Port":80}}]`))
	}))
	defer consul.Close()

	resolver := NewOriginationConsulResolver(&conf.Origination{Consul: originationConsulFromURL(consul.URL)})
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	target, err := resolver.Resolve(ctx)
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if target != "http://origination-api.lendora-sta-origination-api.svc.cluster.local:80" {
		t.Fatalf("target = %q", target)
	}
}

func TestOriginationGRPCConsulResolver_ResolvePrefersGrpcPortMetadata(t *testing.T) {
	consul := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/health/service/origination-api" {
			t.Fatalf("path = %q", r.URL.Path)
		}
		if r.URL.Query().Get("passing") != "true" {
			t.Fatalf("passing = %q", r.URL.Query().Get("passing"))
		}
		_, _ = w.Write([]byte(`[{"Node":{"Address":"10.0.0.10"},"Service":{"Address":"origination-api.lendora-sta-origination-api.svc.cluster.local","Port":80,"Meta":{"grpc_port":"9001"}}}]`))
	}))
	defer consul.Close()

	resolver := NewOriginationGRPCConsulResolver(&conf.Origination{Consul: originationConsulFromURL(consul.URL)})
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	target, err := resolver.Resolve(ctx)
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if target != "origination-api.lendora-sta-origination-api.svc.cluster.local:9001" {
		t.Fatalf("target = %q", target)
	}
}

func originationConsulFromURL(raw string) conf.Consul {
	consul := consulFromURL(raw)
	consul.ServiceName = "origination-api"
	return consul
}
