package observability

import (
	"context"
	"encoding/hex"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/spark/bffkit"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/propagation"
	oteltrace "go.opentelemetry.io/otel/trace"
	"go.opentelemetry.io/otel/trace/noop"
	tracepb "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	tracev1 "go.opentelemetry.io/proto/otlp/trace/v1"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/proto"

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
	fields := strings.Join(otel.GetTextMapPropagator().Fields(), ",")
	if !strings.Contains(fields, "traceparent") {
		t.Fatalf("propagator fields = %q, want traceparent", fields)
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
		TracesHeaders:  "authorization=bearer token",
	}, "fides-bff", "dev")
	if err != nil {
		t.Fatalf("setup: %v", err)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
}

func TestSetup_OTLPExportsRemoteParentServerSpan(t *testing.T) {
	originalProvider := otel.GetTracerProvider()
	originalPropagator := otel.GetTextMapPropagator()
	t.Cleanup(func() {
		otel.SetTracerProvider(originalProvider)
		otel.SetTextMapPropagator(originalPropagator)
	})

	requests := make(chan *tracepb.ExportTraceServiceRequest, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Errorf("read request body: %v", err)
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		if r.URL.Path == "/v1/traces" {
			var request tracepb.ExportTraceServiceRequest
			if err := proto.Unmarshal(body, &request); err != nil {
				t.Errorf("unmarshal OTLP trace request: %v", err)
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			requests <- &request
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	shutdown, err := Setup(context.Background(), conf.OTel{
		TracesExporter:     "otlp",
		TracesEndpoint:     server.URL + "/v1/traces",
		TracesProtocol:     "http/protobuf",
		TracesSampler:      "parentbased_traceidratio",
		TracesSamplerArg:   1.0,
		ResourceAttributes: "deployment.environment=dev-1",
	}, "fides-bff", "dev")
	if err != nil {
		t.Fatalf("setup: %v", err)
	}

	traceID := "4bf92f3577b34da6a3ce929d0e0e4736"
	parentSpanID := "00f067aa0ba902b7"
	ctx := otel.GetTextMapPropagator().Extract(context.Background(), propagation.MapCarrier{
		"traceparent": "00-" + traceID + "-" + parentSpanID + "-01",
	})
	ctx, span := otel.Tracer("github.com/spark/fides-bff/internal/observability").Start(ctx,
		"POST /api/v1/auth/otp:send",
		oteltrace.WithSpanKind(oteltrace.SpanKindServer),
		oteltrace.WithAttributes(attribute.String("http.route", "/api/v1/auth/otp:send")),
	)
	if got := span.SpanContext().TraceID().String(); got != traceID {
		t.Fatalf("otel span trace id = %s, want %s", got, traceID)
	}
	span.End()
	if err := shutdown(ctx); err != nil {
		t.Fatalf("shutdown: %v", err)
	}

	select {
	case request := <-requests:
		span := findOTLPSpan(request, "POST /api/v1/auth/otp:send")
		if span == nil {
			t.Fatalf("OTLP request does not contain server span: %v", request)
		}
		if got := hex.EncodeToString(span.TraceId); got != traceID {
			t.Fatalf("OTLP span trace id = %s, want %s", got, traceID)
		}
		if got := hex.EncodeToString(span.ParentSpanId); got != parentSpanID {
			t.Fatalf("OTLP parent span id = %s, want %s", got, parentSpanID)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for OTLP trace export")
	}
}

func findOTLPSpan(request *tracepb.ExportTraceServiceRequest, name string) *tracev1.Span {
	for _, resourceSpans := range request.ResourceSpans {
		for _, scopeSpans := range resourceSpans.ScopeSpans {
			for _, span := range scopeSpans.Spans {
				if span.Name == name {
					return span
				}
			}
		}
	}
	return nil
}

func TestSetup_RejectsVendorExporter(t *testing.T) {
	_, err := Setup(context.Background(), conf.OTel{
		TracesExporter: "vendor",
		TracesEndpoint: "localhost:4318",
		TracesProtocol: "http/protobuf",
	}, "fides-bff", "dev")
	if err == nil {
		t.Fatal("expected unsupported exporter error")
	}
}

func TestParseHeaders_RejectsBlankEntries(t *testing.T) {
	_, err := parseHeaders("x-otlp-auth=,authorization=bearer token")
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
	got, err := parseHeaders("x-otlp-auth=Bearer%20token%3Dabc%2Cclient%3Dfides-bff")
	if err != nil {
		t.Fatalf("parseHeaders: %v", err)
	}
	want := "Bearer token=abc,client=fides-bff"
	if got["x-otlp-auth"] != want {
		t.Fatalf("x-otlp-auth = %q, want %q", got["x-otlp-auth"], want)
	}
}

func TestDeploymentEnvironment_UsesResourceAttributes(t *testing.T) {
	got := deploymentEnvironment(conf.OTel{ResourceAttributes: "service.namespace=lendora,deployment.environment=dev-1"})
	if got != "dev-1" {
		t.Fatalf("deploymentEnvironment() = %q, want dev-1", got)
	}
}
