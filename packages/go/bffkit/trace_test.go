package bffkit

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	oteltrace "go.opentelemetry.io/otel/trace"
	"google.golang.org/grpc/metadata"
)

type testLogger struct {
	keyvals []any
}

func (l *testLogger) Infow(keyvals ...any) {
	l.keyvals = append(l.keyvals, keyvals...)
}

func TestTraceFilter_setsContextHeadersAndStructuredLogFields(t *testing.T) {
	logger := &testLogger{}
	filter := TraceFilter(logger)
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
}

func TestOutgoingGRPCContext_propagatesTraceMetadata(t *testing.T) {
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
