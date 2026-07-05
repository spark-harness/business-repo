package observability

import (
	"context"
	"errors"
	"strings"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.24.0"

	"github.com/spark/fides-bff/internal/conf"
)

type Shutdown func(context.Context) error

func Setup(ctx context.Context, c conf.OTel, serviceName string, fallbackRelease string) (Shutdown, error) {
	otel.SetTextMapPropagator(propagation.TraceContext{})
	if !c.Enabled {
		return func(context.Context) error { return nil }, nil
	}
	if strings.TrimSpace(c.Endpoint) == "" {
		return nil, errors.New("OBSERVABILITY_OTEL_ENDPOINT is required when OBSERVABILITY_OTEL_ENABLED=true")
	}
	if err := validateHeaders(c.Headers); err != nil {
		return nil, err
	}
	if c.Exporter != "" && c.Exporter != "otlp" {
		return nil, errors.New("unsupported otel exporter")
	}

	exporter, err := newTraceExporter(ctx, c)
	if err != nil {
		return nil, err
	}
	release := firstNonEmpty(c.Release, fallbackRelease)
	res, err := resource.New(ctx,
		resource.WithAttributes(
			semconv.ServiceName(serviceName),
			semconv.ServiceVersion(release),
			semconv.DeploymentEnvironment(c.Environment),
		),
	)
	if err != nil {
		return nil, err
	}

	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(provider)
	return provider.Shutdown, nil
}

func newTraceExporter(ctx context.Context, c conf.OTel) (sdktrace.SpanExporter, error) {
	switch strings.ToLower(firstNonEmpty(c.Protocol, "http/protobuf")) {
	case "http/protobuf", "http":
		opts := []otlptracehttp.Option{httpEndpointOption(c.Endpoint)}
		if headers := nonEmptyHeaders(c.Headers); len(headers) > 0 {
			opts = append(opts, otlptracehttp.WithHeaders(headers))
		}
		if isInsecureEndpoint(c.Endpoint) {
			opts = append(opts, otlptracehttp.WithInsecure())
		}
		return otlptracehttp.New(ctx, opts...)
	case "grpc":
		opts := []otlptracegrpc.Option{grpcEndpointOption(c.Endpoint)}
		if headers := nonEmptyHeaders(c.Headers); len(headers) > 0 {
			opts = append(opts, otlptracegrpc.WithHeaders(headers))
		}
		if isInsecureEndpoint(c.Endpoint) {
			opts = append(opts, otlptracegrpc.WithInsecure())
		}
		return otlptracegrpc.New(ctx, opts...)
	default:
		return nil, errors.New("unsupported otel protocol")
	}
}

func nonEmptyHeaders(headers map[string]string) map[string]string {
	if len(headers) == 0 {
		return nil
	}
	filtered := make(map[string]string, len(headers))
	for key, value := range headers {
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		if key != "" && value != "" {
			filtered[key] = value
		}
	}
	return filtered
}

func validateHeaders(headers map[string]string) error {
	for key, value := range headers {
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		if key == "" || value == "" {
			return errors.New("OBSERVABILITY_OTEL headers require non-empty names and values when OBSERVABILITY_OTEL_ENABLED=true")
		}
	}
	return nil
}

func httpEndpointOption(endpoint string) otlptracehttp.Option {
	if strings.Contains(endpoint, "://") {
		return otlptracehttp.WithEndpointURL(endpoint)
	}
	return otlptracehttp.WithEndpoint(endpoint)
}

func grpcEndpointOption(endpoint string) otlptracegrpc.Option {
	if strings.Contains(endpoint, "://") {
		return otlptracegrpc.WithEndpointURL(endpoint)
	}
	return otlptracegrpc.WithEndpoint(endpoint)
}

func isInsecureEndpoint(endpoint string) bool {
	endpoint = strings.ToLower(endpoint)
	return strings.HasPrefix(endpoint, "http://") || !strings.Contains(endpoint, "://")
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}
