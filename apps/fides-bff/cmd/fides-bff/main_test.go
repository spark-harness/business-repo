package main

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"sync"
	"testing"

	otellog "go.opentelemetry.io/otel/log"
	sdklog "go.opentelemetry.io/otel/sdk/log"
	oteltrace "go.opentelemetry.io/otel/trace"
)

func TestNewLogger_WritesRequiredJSONFields(t *testing.T) {
	var buf bytes.Buffer
	logger := newLogger(&buf, nil)

	logger.Info("startup", slog.String("operation", "bootstrap"))

	var got map[string]any
	if err := json.Unmarshal(bytes.TrimSpace(buf.Bytes()), &got); err != nil {
		t.Fatalf("log line is not valid JSON: %v\n%s", err, buf.String())
	}
	for _, key := range []string{"service.name", "service.version", "level", "timestamp", "message"} {
		if got[key] == "" || got[key] == nil {
			t.Fatalf("missing %s in log JSON: %#v", key, got)
		}
	}
	if got["message"] != "startup" {
		t.Fatalf("message = %#v, want startup", got["message"])
	}
}

func TestNewLogger_FansOutToStdoutAndOTelLogs(t *testing.T) {
	var buf bytes.Buffer
	exporter := &recordingLogExporter{}
	provider := sdklog.NewLoggerProvider(sdklog.WithProcessor(sdklog.NewSimpleProcessor(exporter)))
	t.Cleanup(func() { _ = provider.Shutdown(context.Background()) })
	logger := newLogger(&buf, provider)

	traceID, err := oteltrace.TraceIDFromHex("4bf92f3577b34da6a3ce929d0e0e4736")
	if err != nil {
		t.Fatalf("trace id: %v", err)
	}
	spanID, err := oteltrace.SpanIDFromHex("00f067aa0ba902b7")
	if err != nil {
		t.Fatalf("span id: %v", err)
	}
	ctx := oteltrace.ContextWithSpanContext(context.Background(), oteltrace.NewSpanContext(oteltrace.SpanContextConfig{
		TraceID:    traceID,
		SpanID:     spanID,
		TraceFlags: oteltrace.FlagsSampled,
	}))

	logger.InfoContext(ctx, "http request",
		slog.String("operation", "POST /api/v1/loan-applications"),
		slog.String("trace_id", traceID.String()),
		slog.String("span_id", spanID.String()),
	)

	if !bytes.Contains(buf.Bytes(), []byte(`"message":"http request"`)) {
		t.Fatalf("stdout log missing message: %s", buf.String())
	}
	records := exporter.Records()
	if len(records) != 1 {
		t.Fatalf("exported OTel log records = %d, want 1", len(records))
	}
	if got := records[0].Body().AsString(); got != "http request" {
		t.Fatalf("OTel log body = %q, want http request", got)
	}
	if got := records[0].TraceID(); got != traceID {
		t.Fatalf("OTel log trace id = %s, want %s", got, traceID)
	}
	if got := records[0].SpanID(); got != spanID {
		t.Fatalf("OTel log span id = %s, want %s", got, spanID)
	}
	if !recordHasStringAttr(records[0], "service.name", Name) {
		t.Fatalf("OTel log missing service.name=%s", Name)
	}
	if !recordHasStringAttr(records[0], "operation", "POST /api/v1/loan-applications") {
		t.Fatalf("OTel log missing operation attribute")
	}
}

type recordingLogExporter struct {
	mu      sync.Mutex
	records []sdklog.Record
}

func (e *recordingLogExporter) Export(_ context.Context, records []sdklog.Record) error {
	e.mu.Lock()
	defer e.mu.Unlock()
	for i := range records {
		e.records = append(e.records, records[i].Clone())
	}
	return nil
}

func (e *recordingLogExporter) Shutdown(context.Context) error {
	return nil
}

func (e *recordingLogExporter) ForceFlush(context.Context) error {
	return nil
}

func (e *recordingLogExporter) Records() []sdklog.Record {
	e.mu.Lock()
	defer e.mu.Unlock()
	records := make([]sdklog.Record, len(e.records))
	copy(records, e.records)
	return records
}

func recordHasStringAttr(record sdklog.Record, key string, value string) bool {
	found := false
	record.WalkAttributes(func(attr otellog.KeyValue) bool {
		if attr.Key == key && attr.Value.AsString() == value {
			found = true
			return false
		}
		return true
	})
	return found
}
