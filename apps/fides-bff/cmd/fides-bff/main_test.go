package main

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"testing"
)

func TestNewLogger_WritesRequiredJSONFields(t *testing.T) {
	var buf bytes.Buffer
	logger := newLogger(&buf)

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
