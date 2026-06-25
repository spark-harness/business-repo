package data

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/spark/fides-bff/internal/conf"
)

func TestConsulResolver_ResolveReturnsHealthyServiceAddress(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/health/service/applicant-api" {
			t.Fatalf("path = %q", r.URL.Path)
		}
		if r.URL.Query().Get("passing") != "true" {
			t.Fatalf("passing = %q", r.URL.Query().Get("passing"))
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`[{"Node":{"Address":"10.0.0.10"},"Service":{"Address":"10.0.0.11","Port":9000}}]`))
	}))
	defer server.Close()

	resolver := NewConsulResolver(&conf.Applicant{Consul: consulFromURL(server.URL)})
	target, err := resolver.Resolve(context.Background())
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if target != "10.0.0.11:9000" {
		t.Fatalf("target = %q, want 10.0.0.11:9000", target)
	}
}

func TestConsulResolver_ResolvePrefersGrpcPortMetadata(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`[{"Node":{"Address":"10.0.0.10"},"Service":{"Address":"10.0.0.11","Port":8080,"Meta":{"grpc_port":"9090"}}}]`))
	}))
	defer server.Close()

	resolver := NewConsulResolver(&conf.Applicant{Consul: consulFromURL(server.URL)})
	target, err := resolver.Resolve(context.Background())
	if err != nil {
		t.Fatalf("resolve: %v", err)
	}
	if target != "10.0.0.11:9090" {
		t.Fatalf("target = %q, want 10.0.0.11:9090", target)
	}
}

func TestConsulResolver_ResolveReturnsErrorWhenNoHealthyInstance(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`[]`))
	}))
	defer server.Close()

	resolver := NewConsulResolver(&conf.Applicant{Consul: consulFromURL(server.URL)})
	_, err := resolver.Resolve(context.Background())
	if err == nil {
		t.Fatal("expected no healthy applicant error")
	}
}

func consulFromURL(raw string) conf.Consul {
	parts := strings.SplitN(raw, "://", 2)
	return conf.Consul{Scheme: parts[0], Address: parts[1], ServiceName: "applicant-api"}
}
