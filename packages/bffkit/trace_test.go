package bffkit

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

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
	ctx := ContextWithTraceID(context.Background(), "trace-abc")
	ctx = ContextWithCorrelationID(ctx, "corr-def")

	md, ok := metadata.FromOutgoingContext(OutgoingGRPCContext(ctx))
	if !ok {
		t.Fatal("missing outgoing metadata")
	}
	if got := md.Get("x-trace-id"); len(got) != 1 || got[0] != "trace-abc" {
		t.Fatalf("x-trace-id = %#v, want trace-abc", got)
	}
	if got := md.Get("x-correlation-id"); len(got) != 1 || got[0] != "corr-def" {
		t.Fatalf("x-correlation-id = %#v, want corr-def", got)
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
