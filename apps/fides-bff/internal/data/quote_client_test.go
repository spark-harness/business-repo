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

func TestQuoteClient_CreateQuoteCallsQuoteAPIWithApplicantAndTrace(t *testing.T) {
	var gotApplicantID string
	var gotTraceParent string
	var gotTraceState string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/pricing/quotes" {
			t.Fatalf("path = %q", r.URL.Path)
		}
		gotApplicantID = r.Header.Get(bffkit.HeaderApplicantID)
		gotTraceParent = r.Header.Get(bffkit.HeaderTraceParent)
		gotTraceState = r.Header.Get(bffkit.HeaderTraceState)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"quoteId":"quote_123","monthly":"8560.75","apr":"0.0520","totalInterest":"2729.00","totalPayable":"102729.00","validUntil":"2026-06-28T03:00:00Z"}`))
	}))
	defer server.Close()

	client := NewQuoteClient(&conf.Quote{HTTP: conf.QuoteHTTP{BaseURL: server.URL, Timeout: "1s"}})
	result, err := client.CreateQuote(context.Background(), biz.CreateQuoteCommand{
		ApplicantID: "applicant_001",
		TraceParent: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
		TraceState:  "vendor=state",
		RawRequest:  json.RawMessage(`{"productCode":"PIL","amount":"100000.00","term":12,"purpose":"debt_consolidation"}`),
		ProductCode: "PIL",
		Amount:      json.RawMessage(`"100000.00"`),
		Term:        12,
		Purpose:     "debt_consolidation",
	})
	if err != nil {
		t.Fatalf("CreateQuote() error = %v", err)
	}
	if result.QuoteID != "quote_123" || result.Monthly != "8560.75" || result.APR != "0.0520" {
		t.Fatalf("result = %#v", result)
	}
	if gotApplicantID != "applicant_001" {
		t.Fatalf("x-applicant-id = %q, want applicant_001", gotApplicantID)
	}
	if gotTraceParent == "" || gotTraceParent != "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" {
		t.Fatalf("traceparent = %q", gotTraceParent)
	}
	if gotTraceState != "vendor=state" {
		t.Fatalf("tracestate = %q, want vendor=state", gotTraceState)
	}
}

func TestQuoteClient_CreateQuoteMapsQuoteAPI422(t *testing.T) {
	tests := []struct {
		name     string
		body     string
		wantCode string
	}{
		{"amount out of range", `{"error":"amount_out_of_range"}`, biz.PricingCodeAmountOutOfRange},
		{"validation error", `{"error":"validation_error"}`, biz.PricingCodeValidation},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(http.StatusUnprocessableEntity)
				_, _ = w.Write([]byte(tt.body))
			}))
			defer server.Close()

			client := NewQuoteClient(&conf.Quote{HTTP: conf.QuoteHTTP{BaseURL: server.URL, Timeout: "1s"}})
			_, err := client.CreateQuote(context.Background(), biz.CreateQuoteCommand{ApplicantID: "applicant_001", RawRequest: json.RawMessage(`{}`)})
			pricingErr, ok := err.(*biz.PricingError)
			if !ok {
				t.Fatalf("err = %#v, want PricingError", err)
			}
			if pricingErr.Code != tt.wantCode {
				t.Fatalf("code = %q, want %q", pricingErr.Code, tt.wantCode)
			}
		})
	}
}

func TestQuoteClient_CreateQuoteMapsUnavailable(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	server.Close()

	client := NewQuoteClient(&conf.Quote{HTTP: conf.QuoteHTTP{BaseURL: server.URL, Timeout: "50ms"}})
	_, err := client.CreateQuote(context.Background(), biz.CreateQuoteCommand{ApplicantID: "applicant_001", RawRequest: json.RawMessage(`{}`)})
	pricingErr, ok := err.(*biz.PricingError)
	if !ok {
		t.Fatalf("err = %#v, want PricingError", err)
	}
	if pricingErr.Code != biz.PricingCodeQuoteUnavailable {
		t.Fatalf("code = %q, want %q", pricingErr.Code, biz.PricingCodeQuoteUnavailable)
	}
}

func TestQuoteConsulResolver_ResolveReturnsHTTPServiceURL(t *testing.T) {
	consul := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/health/service/quote-api" {
			t.Fatalf("path = %q", r.URL.Path)
		}
		if r.URL.Query().Get("passing") != "true" {
			t.Fatalf("passing = %q", r.URL.Query().Get("passing"))
		}
		_, _ = w.Write([]byte(`[{"Node":{"Address":"10.0.0.10"},"Service":{"Address":"quote-api.lendora-sta-quote-api.svc.cluster.local","Port":80}}]`))
	}))
	defer consul.Close()

	resolver := NewQuoteConsulResolver(&conf.Quote{Consul: quoteConsulFromURL(consul.URL)})
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	target, err := resolver.Resolve(ctx)
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if target != "http://quote-api.lendora-sta-quote-api.svc.cluster.local:80" {
		t.Fatalf("target = %q", target)
	}
}

func quoteConsulFromURL(raw string) conf.Consul {
	consul := consulFromURL(raw)
	consul.ServiceName = "quote-api"
	return consul
}
