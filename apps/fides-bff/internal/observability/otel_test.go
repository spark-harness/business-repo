package observability

import (
	"context"
	"testing"

	"github.com/spark/bffkit"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	oteltrace "go.opentelemetry.io/otel/trace"
	"go.opentelemetry.io/otel/trace/noop"
	"google.golang.org/grpc/metadata"

	"github.com/spark/fides-bff/internal/conf"
)

func TestSetup_DisabledKeepsNoopProvider(t *testing.T) {
	originalProvider := otel.GetTracerProvider()
	originalPropagator := otel.GetTextMapPropagator()
	t.Cleanup(func() {
		otel.SetTracerProvider(originalProvider)
		otel.SetTextMapPropagator(originalPropagator)
	})
	otel.SetTracerProvider(noop.NewTracerProvider())
	shutdown, err := Setup(context.Background(), conf.OTel{SDKDisabled: true}, "fides-bff", "dev")
	if err != nil {
		t.Fatalf("setup: %v", err)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
	if _, ok := otel.GetTracerProvider().(noop.TracerProvider); !ok {
		t.Fatalf("tracer provider = %T, want noop provider", otel.GetTracerProvider())
	}
	if _, ok := otel.GetTextMapPropagator().(propagation.TraceContext); !ok {
		t.Fatalf("propagator = %T, want TraceContext", otel.GetTextMapPropagator())
	}
}

func TestSetup_DisabledKeepsOutgoingGRPCTraceContext(t *testing.T) {
	originalProvider := otel.GetTracerProvider()
	originalPropagator := otel.GetTextMapPropagator()
	t.Cleanup(func() {
		otel.SetTracerProvider(originalProvider)
		otel.SetTextMapPropagator(originalPropagator)
	})
	otel.SetTextMapPropagator(propagation.Baggage{})
	if _, err := Setup(context.Background(), conf.OTel{TracesExporter: "none"}, "fides-bff", "dev"); err != nil {
		t.Fatalf("setup: %v", err)
	}
	traceID := "4bf92f3577b34da6a3ce929d0e0e4736"
	spanID := "00f067aa0ba902b7"
	parsedTraceID, err := oteltrace.TraceIDFromHex(traceID)
	if err != nil {
		t.Fatalf("trace id: %v", err)
	}
	parsedSpanID, err := oteltrace.SpanIDFromHex(spanID)
	if err != nil {
		t.Fatalf("span id: %v", err)
	}
	ctx := oteltrace.ContextWithSpanContext(context.Background(), oteltrace.NewSpanContext(oteltrace.SpanContextConfig{
		TraceID:    parsedTraceID,
		SpanID:     parsedSpanID,
		TraceFlags: oteltrace.FlagsSampled,
	}))
	ctx = bffkit.ContextWithTraceID(ctx, traceID)

	md, ok := metadata.FromOutgoingContext(bffkit.OutgoingGRPCContext(ctx))
	if !ok {
		t.Fatal("missing outgoing metadata")
	}
	if got := md.Get("traceparent"); len(got) != 1 || got[0] != "00-"+traceID+"-"+spanID+"-01" {
		t.Fatalf("traceparent = %#v, want W3C trace context", got)
	}
}

func TestSetup_MissingEndpointDisablesExporter(t *testing.T) {
	shutdown, err := Setup(context.Background(), conf.OTel{TracesExporter: "otlp"}, "fides-bff", "dev")
	if err != nil {
		t.Fatalf("setup: %v", err)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
}

func TestSetup_RejectsUnsupportedProtocol(t *testing.T) {
	_, err := Setup(context.Background(), conf.OTel{
		TracesExporter: "otlp",
		TracesEndpoint: "localhost:4318",
		TracesProtocol: "zipkin",
	}, "fides-bff", "dev")
	if err == nil {
		t.Fatal("expected unsupported protocol error")
	}
}

func TestSetup_AcceptsFullOTLPEndpointURL(t *testing.T) {
	shutdown, err := Setup(context.Background(), conf.OTel{
		TracesExporter: "otlp",
		TracesEndpoint: "http://localhost:4318/v1/traces",
		TracesProtocol: "http/protobuf",
		TracesHeaders:  "x-sentry-auth=token",
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
		TracesExporter: "sentry",
		TracesEndpoint: "localhost:4318",
		TracesProtocol: "http/protobuf",
	}, "fides-bff", "dev")
	if err == nil {
		t.Fatal("expected unsupported exporter error")
	}
}

func TestParseHeaders_RejectsBlankEntries(t *testing.T) {
	_, err := parseHeaders("x-sentry-auth=,authorization=bearer token")
	if err == nil {
		t.Fatal("expected blank header error")
	}
}

func TestParseHeaders_TrimsEntries(t *testing.T) {
	got, err := parseHeaders(" authorization = bearer token ")
	if err != nil {
		t.Fatalf("parseHeaders: %v", err)
	}
	if len(got) != 1 || got["authorization"] != "bearer token" {
		t.Fatalf("parseHeaders() = %#v", got)
	}
}

func TestParseHeaders_DecodesEscapedEntries(t *testing.T) {
	got, err := parseHeaders("x-sentry-auth=Sentry%20sentry_key%3Dabc%2Csentry_client%3Dfides-bff")
	if err != nil {
		t.Fatalf("parseHeaders: %v", err)
	}
	want := "Sentry sentry_key=abc,sentry_client=fides-bff"
	if got["x-sentry-auth"] != want {
		t.Fatalf("x-sentry-auth = %q, want %q", got["x-sentry-auth"], want)
	}
}

func TestDeploymentEnvironment_UsesResourceAttributes(t *testing.T) {
	got := deploymentEnvironment(conf.OTel{ResourceAttributes: "service.namespace=lendora,deployment.environment=dev-1"})
	if got != "dev-1" {
		t.Fatalf("deploymentEnvironment() = %q, want dev-1", got)
	}
}
