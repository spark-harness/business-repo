package bffkit

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	kerrors "github.com/go-kratos/kratos/v3/errors"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func TestErrorFromGRPC_mapsStatusToHTTPAndStableCode(t *testing.T) {
	tests := []struct {
		name       string
		err        error
		wantStatus int
		wantCode   string
	}{
		{"invalid argument", status.Error(codes.InvalidArgument, "bad field"), http.StatusUnprocessableEntity, CodeValidation},
		{"not found", status.Error(codes.NotFound, "missing"), http.StatusNotFound, CodeNotFound},
		{"already exists", status.Error(codes.AlreadyExists, "duplicate"), http.StatusConflict, CodeConflict},
		{"aborted", status.Error(codes.Aborted, "retry conflict"), http.StatusConflict, CodeConflict},
		{"permission denied", status.Error(codes.PermissionDenied, "denied"), http.StatusForbidden, CodeForbidden},
		{"unauthenticated", status.Error(codes.Unauthenticated, "login required"), http.StatusUnauthorized, CodeUnauthorized},
		{"unknown", status.Error(codes.Unknown, "boom"), http.StatusInternalServerError, CodeInternal},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := ErrorFromGRPC(tt.err)
			if got.Status != tt.wantStatus {
				t.Fatalf("status = %d, want %d", got.Status, tt.wantStatus)
			}
			if got.Code != tt.wantCode {
				t.Fatalf("code = %q, want %q", got.Code, tt.wantCode)
			}
		})
	}
}

func TestErrorFromGRPC_usesControlledPublicMessages(t *testing.T) {
	got := ErrorFromGRPC(status.Error(codes.PermissionDenied, "internal tenant policy user=123 denied"))

	if got.Message != "permission denied" {
		t.Fatalf("message = %q, want controlled public message", got.Message)
	}
}

func TestErrorEncoder_writesEnvelopeWithTraceIDAndValidationDetails(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/api/v1/applications", nil)
	req = req.WithContext(ContextWithTraceID(req.Context(), "trace-test"))
	rec := httptest.NewRecorder()

	ErrorEncoder(rec, req, ValidationError([]FieldError{{Field: "amount", Message: "amount is required"}}))

	if rec.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusUnprocessableEntity)
	}
	var body ErrorEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode envelope: %v", err)
	}
	if body.Error.Code != CodeValidation {
		t.Fatalf("code = %q, want %q", body.Error.Code, CodeValidation)
	}
	if body.Error.TraceID != "trace-test" {
		t.Fatalf("traceId = %q, want trace-test", body.Error.TraceID)
	}
	if len(body.Error.Details) != 1 || body.Error.Details[0].Field != "amount" {
		t.Fatalf("details = %#v, want amount validation detail", body.Error.Details)
	}
}

func TestErrorEncoder_writesRetryAfterInEnvelopeAndHeader(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/otp:send", nil)
	rec := httptest.NewRecorder()

	ErrorEncoder(rec, req, &HTTPError{
		Status:        http.StatusTooManyRequests,
		Code:          "otp_cooldown_active",
		Message:       "cooldown active",
		RetryAfterSec: 42,
	})

	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusTooManyRequests)
	}
	if rec.Header().Get("Retry-After") != "42" {
		t.Fatalf("Retry-After = %q, want 42", rec.Header().Get("Retry-After"))
	}
	var body ErrorEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode envelope: %v", err)
	}
	if body.Error.RetryAfterSec != 42 {
		t.Fatalf("retryAfterSec = %d, want 42", body.Error.RetryAfterSec)
	}
}

func TestErrorEncoder_mapsKratosStatusToStableCode(t *testing.T) {
	tests := []struct {
		name       string
		err        error
		wantStatus int
		wantCode   string
	}{
		{"unauthorized", kerrors.Unauthorized("AUTH", "login required"), http.StatusUnauthorized, CodeUnauthorized},
		{"forbidden", kerrors.Forbidden("PERMISSION", "denied"), http.StatusForbidden, CodeForbidden},
		{"not found", kerrors.NotFound("STATE", "missing"), http.StatusNotFound, CodeNotFound},
		{"conflict", kerrors.Conflict("CONFLICT", "duplicate"), http.StatusConflict, CodeConflict},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodPost, "/api/v1/applications", nil)
			rec := httptest.NewRecorder()

			ErrorEncoder(rec, req, tt.err)

			if rec.Code != tt.wantStatus {
				t.Fatalf("status = %d, want %d", rec.Code, tt.wantStatus)
			}
			var body ErrorEnvelope
			if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
				t.Fatalf("decode envelope: %v", err)
			}
			if body.Error.Code != tt.wantCode {
				t.Fatalf("code = %q, want %q", body.Error.Code, tt.wantCode)
			}
		})
	}
}
