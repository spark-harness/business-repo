package bffkit

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	oteltrace "go.opentelemetry.io/otel/trace"
	"google.golang.org/grpc/metadata"
)

type testLogger struct {
	ctx     context.Context
	keyvals []any
}

func (l *testLogger) InfoContext(ctx context.Context, _ string, keyvals ...any) {
	l.ctx = ctx
	l.keyvals = append(l.keyvals, keyvals...)
}

func TestTraceFilter_setsContextHeadersAndStructuredLogFields(t *testing.T) {
	originalProvider := otel.GetTracerProvider()
	provider := sdktrace.NewTracerProvider()
	otel.SetTracerProvider(provider)
	t.Cleanup(func() {
		_ = provider.Shutdown(context.Background())
		otel.SetTracerProvider(originalProvider)
	})

	logger := &testLogger{}
	filter := TraceFilter(logger, WithDeploymentEnvironment("dev-1"))
	var gotTraceID string
	var gotCorrelationID string
	handler := filter(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotTraceID, _ = TraceIDFromContext(r.Context())
		gotCorrelationID, _ = CorrelationIDFromContext(r.Context())
		w.WriteHeader(http.StatusNoContent)
	}))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	req.Header.Set(HeaderTraceID, "trace-123")
	req.Header.Set(HeaderCorrelationID, "corr-456")
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	if gotTraceID != "trace-123" {
		t.Fatalf("context trace id = %q, want trace-123", gotTraceID)
	}
	if gotCorrelationID != "corr-456" {
		t.Fatalf("context correlation id = %q, want corr-456", gotCorrelationID)
	}
	if rec.Header().Get(HeaderTraceID) != "trace-123" {
		t.Fatalf("response trace header = %q, want trace-123", rec.Header().Get(HeaderTraceID))
	}
	if len(logger.keyvals) == 0 {
		t.Fatal("expected structured access log keyvals")
	}
	if !hasKeyValue(logger.keyvals, "status_code", http.StatusNoContent) {
		t.Fatalf("expected structured access log to include status_code=%d, got %#v", http.StatusNoContent, logger.keyvals)
	}
	if !hasKeyValue(logger.keyvals, "operation", "GET /api/v1/health") {
		t.Fatalf("expected stable operation in access log, got %#v", logger.keyvals)
	}
	if !hasKey(logger.keyvals, "span_id") {
		t.Fatalf("expected access log to include span_id, got %#v", logger.keyvals)
	}
	if spanContext := oteltrace.SpanContextFromContext(logger.ctx); !spanContext.IsValid() {
		t.Fatalf("expected access log context to include valid span context")
	}
	if !hasKeyValue(logger.keyvals, "deployment.environment", "dev-1") {
		t.Fatalf("expected deployment environment in access log, got %#v", logger.keyvals)
	}
}

func TestTraceFilter_logsErrorCodeForFailure(t *testing.T) {
	logger := &testLogger{}
	handler := TraceFilter(logger)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		SetErrorCode(w, CodeValidation)
		w.WriteHeader(http.StatusUnprocessableEntity)
	}))

	req := httptest.NewRequest(http.MethodPost, "/api/v1/applications/123", nil)
	req.SetPathValue("ignored", "ignored")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if !hasKeyValue(logger.keyvals, "error_code", CodeValidation) {
		t.Fatalf("expected error_code in access log, got %#v", logger.keyvals)
	}
	if !hasKeyValue(logger.keyvals, "operation", "POST /api/v1/applications/{id}") {
		t.Fatalf("expected route-pattern operation in access log, got %#v", logger.keyvals)
	}
}

func TestTraceFilter_doesNotLogSensitiveRequestInput(t *testing.T) {
	logger := &testLogger{}
	handler := TraceFilter(logger)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/send-otp?phone=+85251234567&otp=123456", strings.NewReader(`{"token":"secret-token"}`))
	req.Header.Set("Authorization", "Bearer secret-token")
	req.Header.Set("Cookie", "session=secret-cookie")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	for _, forbidden := range []string{"secret-token", "secret-cookie", "+85251234567", "123456"} {
		if containsStringValue(logger.keyvals, forbidden) {
			t.Fatalf("access log leaked sensitive value %q in %#v", forbidden, logger.keyvals)
		}
	}
}

func TestTraceFilter_rejectsSensitiveCorrelationHeaders(t *testing.T) {
	logger := &testLogger{}
	var gotTraceID string
	var gotCorrelationID string
	handler := TraceFilter(logger)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotTraceID, _ = TraceIDFromContext(r.Context())
		gotCorrelationID, _ = CorrelationIDFromContext(r.Context())
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/send-otp", nil)
	req.Header.Set(HeaderTraceID, "secret-token")
	req.Header.Set(HeaderCorrelationID, "85251234567")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if gotTraceID == "secret-token" || gotCorrelationID == "85251234567" {
		t.Fatalf("accepted unsafe correlation headers trace=%q correlation=%q", gotTraceID, gotCorrelationID)
	}
	for _, forbidden := range []string{"secret-token", "85251234567"} {
		if containsStringValue(logger.keyvals, forbidden) {
			t.Fatalf("access log leaked unsafe correlation header %q in %#v", forbidden, logger.keyvals)
		}
	}
}

func TestOutgoingGRPCContext_propagatesTraceMetadata(t *testing.T) {
	originalPropagator := otel.GetTextMapPropagator()
	otel.SetTextMapPropagator(propagation.TraceContext{})
	t.Cleanup(func() {
		otel.SetTextMapPropagator(originalPropagator)
	})

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
		Remote:     false,
	}))
	ctx = ContextWithTraceID(ctx, traceID)
	ctx = ContextWithCorrelationID(ctx, "corr-def")
	ctx = ContextWithPrincipal(ctx, Principal{ApplicantID: "applicant_001"})

	md, ok := metadata.FromOutgoingContext(OutgoingGRPCContext(ctx))
	if !ok {
		t.Fatal("missing outgoing metadata")
	}
	if got := md.Get("x-trace-id"); len(got) != 1 || got[0] != traceID {
		t.Fatalf("x-trace-id = %#v, want %s", got, traceID)
	}
	if got := md.Get("x-correlation-id"); len(got) != 1 || got[0] != "corr-def" {
		t.Fatalf("x-correlation-id = %#v, want corr-def", got)
	}
	if got := md.Get("traceparent"); len(got) != 1 || got[0] != "00-"+traceID+"-"+spanID+"-01" {
		t.Fatalf("traceparent = %#v, want W3C trace context", got)
	}
	if got := md.Get("x-applicant-id"); len(got) != 1 || got[0] != "applicant_001" {
		t.Fatalf("x-applicant-id = %#v, want applicant_001", got)
	}
}

func hasKeyValue(keyvals []any, key string, value any) bool {
	for i := 0; i+1 < len(keyvals); i += 2 {
		if keyvals[i] == key && keyvals[i+1] == value {
			return true
		}
	}
	return false
}

func hasKey(keyvals []any, key string) bool {
	for i := 0; i+1 < len(keyvals); i += 2 {
		if keyvals[i] == key {
			return true
		}
	}
	return false
}

func containsStringValue(keyvals []any, value string) bool {
	for _, keyval := range keyvals {
		if got, ok := keyval.(string); ok && strings.Contains(got, value) {
			return true
		}
	}
	return false
}
