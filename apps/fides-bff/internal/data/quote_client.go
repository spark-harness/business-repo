package data

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/spark/bffkit"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/propagation"
	oteltrace "go.opentelemetry.io/otel/trace"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/conf"
)

var quoteTracer = otel.Tracer("github.com/spark/fides-bff/internal/data")

type QuoteClient struct {
	resolver ServiceResolver
	timeout  time.Duration
	client   *http.Client
}

func NewQuoteClient(c *conf.Quote) *QuoteClient {
	timeout := 3 * time.Second
	if c != nil {
		if parsed, err := time.ParseDuration(c.HTTP.Timeout); err == nil && parsed > 0 {
			timeout = parsed
		}
	}
	return &QuoteClient{
		resolver: newQuoteResolver(c),
		timeout:  timeout,
		client:   &http.Client{},
	}
}

func (c *QuoteClient) CreateQuote(ctx context.Context, command biz.CreateQuoteCommand) (biz.QuoteResult, error) {
	if c == nil || c.resolver == nil {
		return biz.QuoteResult{}, quoteUnavailable()
	}
	ctx, span := quoteTracer.Start(ctx,
		"POST /api/v1/pricing/quotes",
		oteltrace.WithSpanKind(oteltrace.SpanKindClient),
		oteltrace.WithAttributes(
			attribute.String("http.request.method", http.MethodPost),
			attribute.String("server.address", "quote-api"),
		),
	)
	defer span.End()

	if c.timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, c.timeout)
		defer cancel()
	}
	baseURL, err := c.resolver.Resolve(ctx)
	if err != nil {
		span.SetStatus(codes.Error, "resolve quote-api")
		return biz.QuoteResult{}, quoteUnavailable()
	}
	endpoint, err := url.JoinPath(baseURL, "/api/v1/pricing/quotes")
	if err != nil {
		span.SetStatus(codes.Error, "build quote-api url")
		return biz.QuoteResult{}, quoteUnavailable()
	}
	body := command.RawRequest
	if len(body) == 0 {
		body = fallbackQuoteRequest(command)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		span.SetStatus(codes.Error, "build quote-api request")
		return biz.QuoteResult{}, quoteUnavailable()
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(bffkit.HeaderApplicantID, command.ApplicantID)
	propagateTraceHeaders(ctx, req.Header, command)

	resp, err := c.client.Do(req)
	if err != nil {
		span.SetStatus(codes.Error, "call quote-api")
		return biz.QuoteResult{}, quoteUnavailable()
	}
	defer func() { _ = resp.Body.Close() }()
	span.SetAttributes(attribute.Int("http.response.status_code", resp.StatusCode))
	if resp.StatusCode == http.StatusOK {
		var body quoteResponse
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			span.SetStatus(codes.Error, "decode quote-api response")
			return biz.QuoteResult{}, quoteUnavailable()
		}
		return biz.QuoteResult{
			QuoteID:       body.QuoteID,
			Monthly:       body.Monthly,
			APR:           body.APR,
			TotalInterest: body.TotalInterest,
			TotalPayable:  body.TotalPayable,
			ValidUntil:    body.ValidUntil,
		}, nil
	}
	mapped := quoteErrorFromHTTP(resp)
	if mapped.Code == biz.PricingCodeQuoteUnavailable {
		span.SetStatus(codes.Error, mapped.Code)
	}
	return biz.QuoteResult{}, mapped
}

type quoteResponse struct {
	QuoteID       string `json:"quoteId"`
	Monthly       string `json:"monthly"`
	APR           string `json:"apr"`
	TotalInterest string `json:"totalInterest"`
	TotalPayable  string `json:"totalPayable"`
	ValidUntil    string `json:"validUntil"`
}

func newQuoteResolver(c *conf.Quote) ServiceResolver {
	if c != nil && strings.TrimSpace(c.HTTP.BaseURL) != "" {
		return staticURLResolver(strings.TrimRight(strings.TrimSpace(c.HTTP.BaseURL), "/"))
	}
	return NewQuoteConsulResolver(c)
}

type staticURLResolver string

func (r staticURLResolver) Resolve(context.Context) (string, error) {
	if strings.TrimSpace(string(r)) == "" {
		return "", errors.New("quote-api URL is empty")
	}
	return string(r), nil
}

func NewQuoteConsulResolver(c *conf.Quote) *QuoteConsulResolver {
	consul := conf.Consul{}
	if c != nil {
		consul = c.Consul
	}
	scheme := firstNonEmpty(consul.Scheme, "http")
	address := firstNonEmpty(consul.Address, "127.0.0.1:8500")
	return &QuoteConsulResolver{
		baseURL:     scheme + "://" + address,
		serviceName: firstNonEmpty(consul.ServiceName, "quote-api"),
		client:      &http.Client{Timeout: 2 * time.Second},
	}
}

type QuoteConsulResolver struct {
	baseURL     string
	serviceName string
	client      *http.Client
}

func (r *QuoteConsulResolver) Resolve(ctx context.Context) (string, error) {
	if r == nil {
		return "", errors.New("quote consul resolver is not configured")
	}
	endpoint, err := url.JoinPath(r.baseURL, "/v1/health/service", r.serviceName)
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint+"?passing=true", nil)
	if err != nil {
		return "", err
	}
	resp, err := r.client.Do(req)
	if err != nil {
		return "", err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("consul health status %d", resp.StatusCode)
	}
	var entries []consulHealthEntry
	if err := json.NewDecoder(resp.Body).Decode(&entries); err != nil {
		return "", err
	}
	for _, entry := range entries {
		address := firstNonEmpty(entry.Service.Address, entry.Node.Address)
		port := entry.Service.Port
		if address == "" || port == 0 {
			continue
		}
		return "http://" + net.JoinHostPort(address, strconv.Itoa(port)), nil
	}
	return "", errors.New("no healthy quote-api instance")
}

func propagateTraceHeaders(ctx context.Context, header http.Header, command biz.CreateQuoteCommand) {
	carrier := propagation.HeaderCarrier(header)
	propagation.TraceContext{}.Inject(ctx, carrier)
	if command.TraceParent != "" {
		header.Set(bffkit.HeaderTraceParent, command.TraceParent)
	}
	if command.TraceState != "" {
		header.Set(bffkit.HeaderTraceState, command.TraceState)
	}
}

func fallbackQuoteRequest(command biz.CreateQuoteCommand) []byte {
	body := map[string]any{
		"productCode": command.ProductCode,
		"amount":      command.Amount,
		"term":        command.Term,
		"purpose":     command.Purpose,
	}
	data, err := json.Marshal(body)
	if err != nil {
		return []byte("{}")
	}
	return data
}

func quoteErrorFromHTTP(resp *http.Response) *biz.PricingError {
	if resp.StatusCode == http.StatusUnprocessableEntity {
		var body struct {
			Error string `json:"error"`
			Code  string `json:"code"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&body); err == nil {
			code := firstNonEmpty(body.Error, body.Code)
			switch code {
			case biz.PricingCodeAmountOutOfRange:
				return &biz.PricingError{Code: biz.PricingCodeAmountOutOfRange, Message: "amount out of range"}
			case biz.PricingCodeValidation:
				return &biz.PricingError{Code: biz.PricingCodeValidation, Message: "validation failed"}
			}
		}
	}
	return quoteUnavailable()
}

func quoteUnavailable() *biz.PricingError {
	return &biz.PricingError{Code: biz.PricingCodeQuoteUnavailable, Message: "quote-api is unavailable"}
}
