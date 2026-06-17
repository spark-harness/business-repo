package bffkit

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"regexp"
	"strings"
	"time"

	khttp "github.com/go-kratos/kratos/v2/transport/http"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/propagation"
	oteltrace "go.opentelemetry.io/otel/trace"
	"google.golang.org/grpc/metadata"
)

const (
	HeaderTraceID       = "X-Trace-Id"
	HeaderCorrelationID = "X-Correlation-Id"
)

type contextKey string

const (
	traceIDKey       contextKey = "bffkit.trace_id"
	correlationIDKey contextKey = "bffkit.correlation_id"
)

type AccessLogger interface {
	Infow(keyvals ...any)
}

var (
	tracer              = otel.Tracer("github.com/spark/bffkit")
	meter               = otel.Meter("github.com/spark/bffkit")
	httpServerRequests  metric.Int64Counter
	httpServerDurations metric.Float64Histogram
)

func init() {
	httpServerRequests, _ = meter.Int64Counter("http.server.requests")
	httpServerDurations, _ = meter.Float64Histogram("http.server.duration")
}

func TraceFilter(logger AccessLogger) khttp.FilterFunc {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			traceID := firstNonEmpty(r.Header.Get(HeaderTraceID), traceIDFromTraceparent(r.Header.Get("traceparent")), newTraceID())
			correlationID := firstNonEmpty(r.Header.Get(HeaderCorrelationID), traceID)
			route := routePattern(r.URL.Path)
			operation := r.Method + " " + route
			ctx := propagation.TraceContext{}.Extract(r.Context(), propagation.HeaderCarrier(r.Header))
			ctx, span := tracer.Start(ctx, operation,
				oteltrace.WithSpanKind(oteltrace.SpanKindServer),
				oteltrace.WithAttributes(
					attribute.String("http.request.method", r.Method),
					attribute.String("http.route", route),
					attribute.String("trace_id", traceID),
					attribute.String("request_id", correlationID),
				),
			)
			defer span.End()

			ctx = ContextWithTraceID(ctx, traceID)
			ctx = ContextWithCorrelationID(ctx, correlationID)

			w.Header().Set(HeaderTraceID, traceID)
			w.Header().Set(HeaderCorrelationID, correlationID)
			recorder := newStatusRecorder(w)
			next.ServeHTTP(recorder, r.WithContext(ctx))

			statusCode := recorder.status
			attrs := []attribute.KeyValue{
				attribute.String("http.request.method", r.Method),
				attribute.String("http.route", route),
				attribute.Int("http.response.status_code", statusCode),
			}
			if recorder.errorCode != "" {
				attrs = append(attrs, attribute.String("error_code", recorder.errorCode))
			}
			if statusCode >= http.StatusInternalServerError {
				span.SetStatus(codes.Error, http.StatusText(statusCode))
			}
			if statusCode >= http.StatusBadRequest {
				span.SetAttributes(attribute.Bool("error", true))
			}
			if recorder.errorCode != "" {
				span.SetAttributes(attribute.String("error_code", recorder.errorCode))
			}
			httpServerRequests.Add(ctx, 1, metric.WithAttributes(attrs...))
			httpServerDurations.Record(ctx, time.Since(start).Seconds(), metric.WithAttributes(attrs...))

			if logger != nil {
				keyvals := []any{
					"operation", r.Method + " " + r.URL.Path,
					"trace_id", traceID,
					"request_id", correlationID,
					"status_code", statusCode,
					"latency_ms", time.Since(start).Milliseconds(),
				}
				keyvals[1] = operation
				if recorder.errorCode != "" {
					keyvals = append(keyvals, "error_code", recorder.errorCode)
				}
				logger.Infow(keyvals...)
			}
		})
	}
}

type statusRecorder struct {
	http.ResponseWriter
	status    int
	errorCode string
}

func newStatusRecorder(w http.ResponseWriter) *statusRecorder {
	return &statusRecorder{ResponseWriter: w, status: http.StatusOK}
}

func (w *statusRecorder) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *statusRecorder) SetErrorCode(code string) {
	w.errorCode = code
}

type errorCodeSetter interface {
	SetErrorCode(code string)
}

func SetErrorCode(w http.ResponseWriter, code string) {
	if setter, ok := w.(errorCodeSetter); ok {
		setter.SetErrorCode(code)
	}
}

func ContextWithTraceID(ctx context.Context, traceID string) context.Context {
	return context.WithValue(ctx, traceIDKey, traceID)
}

func TraceIDFromContext(ctx context.Context) (string, bool) {
	traceID, ok := ctx.Value(traceIDKey).(string)
	return traceID, ok && traceID != ""
}

func ContextWithCorrelationID(ctx context.Context, correlationID string) context.Context {
	return context.WithValue(ctx, correlationIDKey, correlationID)
}

func CorrelationIDFromContext(ctx context.Context) (string, bool) {
	correlationID, ok := ctx.Value(correlationIDKey).(string)
	return correlationID, ok && correlationID != ""
}

func OutgoingGRPCContext(ctx context.Context) context.Context {
	traceID, _ := TraceIDFromContext(ctx)
	correlationID, _ := CorrelationIDFromContext(ctx)
	kvs := make([]string, 0, 4)
	if traceID != "" {
		kvs = append(kvs, strings.ToLower(HeaderTraceID), traceID)
	}
	if correlationID != "" {
		kvs = append(kvs, strings.ToLower(HeaderCorrelationID), correlationID)
	}
	if len(kvs) == 0 {
		return ctx
	}
	return metadata.AppendToOutgoingContext(ctx, kvs...)
}

func traceIDFromTraceparent(traceparent string) string {
	parts := strings.Split(traceparent, "-")
	if len(parts) >= 2 && len(parts[1]) == 32 {
		return parts[1]
	}
	return ""
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func newTraceID() string {
	var buf [16]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "trace-unavailable"
	}
	return hex.EncodeToString(buf[:])
}

var pathVariablePattern = regexp.MustCompile(`(?i)^[0-9a-f]{8,}$|^[0-9]+$|^[0-9a-f-]{16,}$`)

func routePattern(path string) string {
	if path == "" || path == "/" {
		return path
	}
	segments := strings.Split(path, "/")
	for i := 1; i < len(segments); i++ {
		if pathVariablePattern.MatchString(segments[i]) {
			segments[i] = "{id}"
		}
	}
	return strings.Join(segments, "/")
}
