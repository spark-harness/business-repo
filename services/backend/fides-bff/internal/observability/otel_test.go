package observability

import (
	"context"
	"testing"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/trace/noop"

	"github.com/spark/fides-bff/internal/conf"
)

func TestSetup_DisabledOrMissingEndpointKeepsNoopProvider(t *testing.T) {
	otel.SetTracerProvider(noop.NewTracerProvider())
	shutdown, err := Setup(context.Background(), conf.OTel{Enabled: true}, "fides-bff", "dev")
	if err != nil {
		t.Fatalf("setup: %v", err)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
	if _, ok := otel.GetTracerProvider().(noop.TracerProvider); !ok {
		t.Fatalf("tracer provider = %T, want noop provider", otel.GetTracerProvider())
	}
}

func TestSetup_RejectsUnsupportedProtocol(t *testing.T) {
	_, err := Setup(context.Background(), conf.OTel{
		Enabled:  true,
		Exporter: "otlp",
		Endpoint: "localhost:4318",
		Protocol: "zipkin",
	}, "fides-bff", "dev")
	if err == nil {
		t.Fatal("expected unsupported protocol error")
	}
}

func TestSetup_AcceptsFullOTLPEndpointURL(t *testing.T) {
	shutdown, err := Setup(context.Background(), conf.OTel{
		Enabled:  true,
		Exporter: "otlp",
		Endpoint: "http://localhost:4318/v1/traces",
		Protocol: "http/protobuf",
	}, "fides-bff", "dev")
	if err != nil {
		t.Fatalf("setup: %v", err)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
}

func TestSetup_RejectsVendorExporter(t *testing.T) {
	_, err := Setup(context.Background(), conf.OTel{
		Enabled:  true,
		Exporter: "sentry",
		Endpoint: "localhost:4318",
		Protocol: "http/protobuf",
	}, "fides-bff", "dev")
	if err == nil {
		t.Fatal("expected unsupported exporter error")
	}
}
