package bffkit

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	kerrors "github.com/go-kratos/kratos/v2/errors"
	"github.com/go-kratos/kratos/v2/transport/http/binding"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

const (
	CodeValidation   = "BFF-PARAM-0001"
	CodeNotFound     = "BFF-STATE-0001"
	CodeConflict     = "BFF-CONFLICT-0001"
	CodeForbidden    = "BFF-PERMISSION-0001"
	CodeUnauthorized = "BFF-AUTH-0001"
	CodeInternal     = "BFF-SYSTEM-0001"
)

type FieldError struct {
	Field   string `json:"field"`
	Message string `json:"message"`
}

type ErrorEnvelope struct {
	Error ErrorBody `json:"error"`
}

type ErrorBody struct {
	Code          string       `json:"code"`
	Message       string       `json:"message"`
	Field         string       `json:"field,omitempty"`
	TraceID       string       `json:"traceId,omitempty"`
	RetryAfterSec int          `json:"retryAfterSec,omitempty"`
	Details       []FieldError `json:"details,omitempty"`
}

type HTTPError struct {
	Status        int
	Code          string
	Message       string
	Field         string
	RetryAfterSec int
	Details       []FieldError
}

func (e *HTTPError) Error() string {
	return e.Message
}

func ValidationError(details []FieldError) *HTTPError {
	message := "validation failed"
	field := ""
	if len(details) == 1 {
		field = details[0].Field
		message = details[0].Message
	}
	return &HTTPError{
		Status:  http.StatusUnprocessableEntity,
		Code:    CodeValidation,
		Message: message,
		Field:   field,
		Details: details,
	}
}

func ErrorFromGRPC(err error) *HTTPError {
	if err == nil {
		return nil
	}
	st, ok := status.FromError(err)
	if !ok {
		return &HTTPError{Status: http.StatusInternalServerError, Code: CodeInternal, Message: "internal error"}
	}

	switch st.Code() {
	case codes.InvalidArgument:
		return &HTTPError{Status: http.StatusUnprocessableEntity, Code: CodeValidation, Message: "validation failed"}
	case codes.NotFound:
		return &HTTPError{Status: http.StatusNotFound, Code: CodeNotFound, Message: "resource not found"}
	case codes.AlreadyExists, codes.Aborted:
		return &HTTPError{Status: http.StatusConflict, Code: CodeConflict, Message: "request conflict"}
	case codes.PermissionDenied:
		return &HTTPError{Status: http.StatusForbidden, Code: CodeForbidden, Message: "permission denied"}
	case codes.Unauthenticated:
		return &HTTPError{Status: http.StatusUnauthorized, Code: CodeUnauthorized, Message: "authentication required"}
	default:
		return &HTTPError{Status: http.StatusInternalServerError, Code: CodeInternal, Message: "internal error"}
	}
}

func ErrorEncoder(w http.ResponseWriter, r *http.Request, err error) {
	he := normalizeError(err)
	traceID, _ := TraceIDFromContext(r.Context())
	body := ErrorEnvelope{Error: ErrorBody{
		Code:          he.Code,
		Message:       he.Message,
		Field:         he.Field,
		TraceID:       traceID,
		RetryAfterSec: he.RetryAfterSec,
		Details:       he.Details,
	}}

	SetErrorCode(w, he.Code)
	if he.RetryAfterSec > 0 {
		w.Header().Set("Retry-After", strconv.Itoa(he.RetryAfterSec))
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(he.Status)
	_ = json.NewEncoder(w).Encode(body)
}

func normalizeError(err error) *HTTPError {
	var he *HTTPError
	if errors.As(err, &he) {
		return he
	}
	if st, ok := status.FromError(err); ok {
		return ErrorFromGRPC(st.Err())
	}
	if se := kerrors.FromError(err); se != nil {
		return &HTTPError{Status: int(se.Code), Code: codeFromHTTPStatus(int(se.Code)), Message: se.Message}
	}
	return &HTTPError{Status: http.StatusInternalServerError, Code: CodeInternal, Message: "internal error"}
}

func codeFromHTTPStatus(status int) string {
	switch status {
	case http.StatusBadRequest, http.StatusUnprocessableEntity:
		return CodeValidation
	case http.StatusUnauthorized:
		return CodeUnauthorized
	case http.StatusForbidden:
		return CodeForbidden
	case http.StatusNotFound:
		return CodeNotFound
	case http.StatusConflict:
		return CodeConflict
	default:
		return CodeInternal
	}
}

func RequestDecoder(r *http.Request, v any) error {
	if err := binding.BindForm(r, v); err != nil {
		return ValidationError([]FieldError{{Field: "", Message: err.Error()}})
	}
	return nil
}
