package observability

import (
	"context"
	"errors"
	"net/url"
	"strings"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploghttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	otellog "go.opentelemetry.io/otel/log"
	"go.opentelemetry.io/otel/propagation"
	sdklog "go.opentelemetry.io/otel/sdk/log"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.24.0"

	"github.com/spark/fides-bff/internal/conf"
)

type Shutdown func(context.Context) error

func Setup(ctx context.Context, c conf.OTel, serviceName string, fallbackRelease string) (Shutdown, error) {
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
	))
	exporterName := strings.ToLower(firstNonEmpty(c.TracesExporter, "none"))
	if c.SDKDisabled || exporterName == "none" {
		return func(context.Context) error { return nil }, nil
	}
	if strings.TrimSpace(c.TracesEndpoint) == "" {
		return func(context.Context) error { return nil }, nil
	}
	headers, err := parseHeaders(c.TracesHeaders)
	if err != nil {
		return nil, err
	}
	if exporterName != "otlp" {
		return nil, errors.New("unsupported otel exporter")
	}

	exporter, err := newTraceExporter(ctx, c, headers)
	if err != nil {
		return nil, err
	}
	res, err := newResource(ctx, c, serviceName, fallbackRelease)
	if err != nil {
		return nil, err
	}

	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(provider)
	return func(ctx context.Context) error {
		return provider.Shutdown(ctx)
	}, nil
}

func SetupLogs(ctx context.Context, c conf.OTel, serviceName string, fallbackRelease string) (otellog.LoggerProvider, Shutdown, error) {
	exporterName := strings.ToLower(firstNonEmpty(c.LogsExporter, "none"))
	if c.SDKDisabled || exporterName == "none" {
		return nil, func(context.Context) error { return nil }, nil
	}
	if strings.TrimSpace(c.LogsEndpoint) == "" {
		return nil, func(context.Context) error { return nil }, nil
	}
	headers, err := parseHeaders(c.LogsHeaders)
	if err != nil {
		return nil, nil, err
	}
	if exporterName != "otlp" {
		return nil, nil, errors.New("unsupported otel logs exporter")
	}
	exporter, err := newLogExporter(ctx, c, headers)
	if err != nil {
		return nil, nil, err
	}
	res, err := newResource(ctx, c, serviceName, fallbackRelease)
	if err != nil {
		return nil, nil, err
	}
	provider := sdklog.NewLoggerProvider(
		sdklog.WithProcessor(sdklog.NewBatchProcessor(exporter)),
		sdklog.WithResource(res),
	)
	return provider, provider.Shutdown, nil
}

func newTraceExporter(ctx context.Context, c conf.OTel, headers map[string]string) (sdktrace.SpanExporter, error) {
	switch strings.ToLower(firstNonEmpty(c.TracesProtocol, "http/protobuf")) {
	case "http/protobuf", "http":
		opts := []otlptracehttp.Option{httpEndpointOption(c.TracesEndpoint)}
		if len(headers) > 0 {
			opts = append(opts, otlptracehttp.WithHeaders(headers))
		}
		if isInsecureEndpoint(c.TracesEndpoint) {
			opts = append(opts, otlptracehttp.WithInsecure())
		}
		return otlptracehttp.New(ctx, opts...)
	case "grpc":
		opts := []otlptracegrpc.Option{grpcEndpointOption(c.TracesEndpoint)}
		if len(headers) > 0 {
			opts = append(opts, otlptracegrpc.WithHeaders(headers))
		}
		if isInsecureEndpoint(c.TracesEndpoint) {
			opts = append(opts, otlptracegrpc.WithInsecure())
		}
		return otlptracegrpc.New(ctx, opts...)
	default:
		return nil, errors.New("unsupported otel protocol")
	}
}

func newLogExporter(ctx context.Context, c conf.OTel, headers map[string]string) (sdklog.Exporter, error) {
	switch strings.ToLower(firstNonEmpty(c.LogsProtocol, "http/protobuf")) {
	case "http/protobuf", "http":
		opts := []otlploghttp.Option{logHTTPEndpointOption(c.LogsEndpoint)}
		if len(headers) > 0 {
			opts = append(opts, otlploghttp.WithHeaders(headers))
		}
		if isInsecureEndpoint(c.LogsEndpoint) {
			opts = append(opts, otlploghttp.WithInsecure())
		}
		return otlploghttp.New(ctx, opts...)
	default:
		return nil, errors.New("unsupported otel logs protocol")
	}
}

func parseHeaders(raw string) (map[string]string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, nil
	}
	headers := map[string]string{}
	for _, entry := range strings.Split(raw, ",") {
		key, value, ok := strings.Cut(entry, "=")
		if !ok {
			return nil, errors.New("OTEL_EXPORTER_OTLP_TRACES_HEADERS entries must use key=value")
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		if key == "" || value == "" {
			return nil, errors.New("OTEL_EXPORTER_OTLP_TRACES_HEADERS requires non-empty names and values")
		}
		decodedKey, err := url.QueryUnescape(key)
		if err != nil {
			return nil, errors.New("OTEL_EXPORTER_OTLP_TRACES_HEADERS contains invalid escaped key")
		}
		decodedValue, err := url.QueryUnescape(value)
		if err != nil {
			return nil, errors.New("OTEL_EXPORTER_OTLP_TRACES_HEADERS contains invalid escaped value")
		}
		key = strings.TrimSpace(decodedKey)
		value = strings.TrimSpace(decodedValue)
		if key == "" || value == "" {
			return nil, errors.New("OTEL_EXPORTER_OTLP_TRACES_HEADERS requires non-empty decoded names and values")
		}
		headers[key] = value
	}
	return headers, nil
}

func deploymentEnvironment(c conf.OTel) string {
	return resourceAttribute(c.ResourceAttributes, "deployment.environment")
}

func newResource(ctx context.Context, c conf.OTel, serviceName string, fallbackRelease string) (*resource.Resource, error) {
	release := firstNonEmpty(c.ServiceVersion, fallbackRelease)
	service := firstNonEmpty(c.ServiceName, serviceName)
	environment := deploymentEnvironment(c)
	attrs := []attribute.KeyValue{
		semconv.ServiceName(service),
		semconv.ServiceVersion(release),
	}
	if environment != "" {
		attrs = append(attrs, semconv.DeploymentEnvironment(environment))
	}
	return resource.New(ctx, resource.WithAttributes(attrs...))
}

func resourceAttribute(raw string, key string) string {
	for _, entry := range strings.Split(raw, ",") {
		name, value, ok := strings.Cut(entry, "=")
		if !ok {
			continue
		}
		if strings.TrimSpace(name) == key {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

func httpEndpointOption(endpoint string) otlptracehttp.Option {
	if strings.Contains(endpoint, "://") {
		return otlptracehttp.WithEndpointURL(endpoint)
	}
	return otlptracehttp.WithEndpoint(endpoint)
}

func logHTTPEndpointOption(endpoint string) otlploghttp.Option {
	if strings.Contains(endpoint, "://") {
		return otlploghttp.WithEndpointURL(endpoint)
	}
	return otlploghttp.WithEndpoint(endpoint)
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
