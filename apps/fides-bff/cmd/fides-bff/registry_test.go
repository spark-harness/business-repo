package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/go-kratos/kratos/v3/registry"

	"github.com/spark/fides-bff/internal/conf"
)

func TestNewConsulRegistrarRegistersFidesBFF(t *testing.T) {
	var registered bool
	var deregistered bool
	consul := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPut && r.URL.Path == "/v1/agent/service/register":
			var body struct {
				Name    string            `json:"Name"`
				Address string            `json:"Address"`
				Port    int               `json:"Port"`
				Meta    map[string]string `json:"Meta"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Fatalf("decode register body: %v", err)
			}
			if body.Name != Name || body.Address != "127.0.0.1" || body.Port != 8000 || body.Meta["module"] != "frontend" {
				t.Fatalf("register body = %#v", body)
			}
			registered = true
			w.WriteHeader(http.StatusOK)
		case r.Method == http.MethodPut && r.URL.Path == "/v1/agent/service/deregister/fides-bff-test":
			deregistered = true
			w.WriteHeader(http.StatusOK)
		case r.Method == http.MethodPut && r.URL.Path == "/v1/agent/check/pass/service:fides-bff-test":
			w.WriteHeader(http.StatusOK)
		case r.Method == http.MethodPut && r.URL.Path == "/v1/agent/check/update/service:fides-bff-test":
			w.WriteHeader(http.StatusOK)
		default:
			t.Fatalf("unexpected consul request %s %s", r.Method, r.URL.String())
		}
	}))
	defer consul.Close()

	registration, err := newConsulRegistration(registryConfigFromURL(consul.URL))
	if err != nil {
		t.Fatalf("new registration: %v", err)
	}
	if registration.registrar == nil {
		t.Fatal("expected registrar")
	}
	if registration.endpoint.String() != "http://127.0.0.1:8000" {
		t.Fatalf("endpoint = %q", registration.endpoint.String())
	}

	instance := &registry.ServiceInstance{
		ID:        "fides-bff-test",
		Name:      Name,
		Version:   "test",
		Endpoints: []string{registration.endpoint.String()},
		Metadata:  registration.metadata,
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()

	if err := registration.registrar.Register(ctx, instance); err != nil {
		t.Fatalf("register: %v", err)
	}
	if !registered {
		t.Fatal("expected service register request")
	}
	if err := registration.registrar.Deregister(ctx, instance); err != nil {
		t.Fatalf("deregister: %v", err)
	}
	if !deregistered {
		t.Fatal("expected service deregister request")
	}
}

func TestNewConsulRegistrarUsesConfiguredServiceName(t *testing.T) {
	registration, err := newConsulRegistration(&conf.Registry{
		Consul: conf.ServiceRegistryConsul{
			Enabled:       true,
			Address:       "127.0.0.1:8500",
			Scheme:        "http",
			ServiceName:   "dev-1-fides-bff",
			DiscoveryAddr: "127.0.0.1:8000",
		},
	})
	if err != nil {
		t.Fatalf("new registration: %v", err)
	}
	if registration.name != "dev-1-fides-bff" {
		t.Fatalf("service name = %q", registration.name)
	}
}

func TestNewConsulRegistrarDisabled(t *testing.T) {
	registration, err := newConsulRegistration(&conf.Registry{Consul: conf.ServiceRegistryConsul{Enabled: false}})
	if err != nil {
		t.Fatalf("new registration: %v", err)
	}
	if registration.registrar != nil {
		t.Fatal("expected nil registrar when disabled")
	}
	if registration.endpoint != nil {
		t.Fatal("expected nil endpoint when disabled")
	}
}

func registryConfigFromURL(raw string) *conf.Registry {
	parsed := consulURL(raw)
	return &conf.Registry{
		Consul: conf.ServiceRegistryConsul{
			Enabled:                true,
			Address:                parsed.address,
			Scheme:                 parsed.scheme,
			DiscoveryAddr:          "127.0.0.1:8000",
			Heartbeat:              true,
			HealthCheck:            true,
			HealthCheckIntervalSec: 1,
			DeregisterAfterSec:     2,
			Metadata: map[string]string{
				"module": "frontend",
			},
		},
	}
}

type parsedConsulURL struct {
	scheme  string
	address string
}

func consulURL(raw string) parsedConsulURL {
	for _, prefix := range []string{"http://", "https://"} {
		if len(raw) > len(prefix) && raw[:len(prefix)] == prefix {
			return parsedConsulURL{scheme: prefix[:len(prefix)-3], address: raw[len(prefix):]}
		}
	}
	return parsedConsulURL{scheme: "http", address: raw}
}
