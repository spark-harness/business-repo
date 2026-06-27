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
	oteltrace "go.opentelemetry.io/otel/trace"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/conf"
)

var originationTracer = otel.Tracer("github.com/spark/fides-bff/internal/data")

type OriginationClient struct {
	resolver ServiceResolver
	timeout  time.Duration
	client   *http.Client
}

func NewOriginationClient(c *conf.Origination) *OriginationClient {
	timeout := 3 * time.Second
	if c != nil {
		if parsed, err := time.ParseDuration(c.HTTP.Timeout); err == nil && parsed > 0 {
			timeout = parsed
		}
	}
	return &OriginationClient{
		resolver: newOriginationResolver(c),
		timeout:  timeout,
		client:   &http.Client{},
	}
}

func (c *OriginationClient) CreateLoanApplication(ctx context.Context, command biz.CreateLoanApplicationCommand) (biz.LoanApplicationSummary, error) {
	var result loanApplicationSummaryResponse
	err := c.doJSON(ctx, http.MethodPost, "/api/v1/loan-applications", commandHeaders{
		applicantID:    command.ApplicantID,
		idempotencyKey: command.IdempotencyKey,
		traceParent:    command.TraceParent,
		traceState:     command.TraceState,
	}, command.RawRequest, &result)
	if err != nil {
		return biz.LoanApplicationSummary{}, err
	}
	return biz.LoanApplicationSummary{
		ApplicationID: result.ApplicationID,
		Status:        result.Status,
		CurrentStep:   result.CurrentStep,
	}, nil
}

func (c *OriginationClient) GetLoanApplication(ctx context.Context, command biz.GetLoanApplicationCommand) (biz.LoanApplicationDetail, error) {
	var result loanApplicationDetailResponse
	err := c.doJSON(ctx, http.MethodGet, "/api/v1/loan-applications/"+url.PathEscape(command.ApplicationID), commandHeaders{
		applicantID: command.ApplicantID,
		traceParent: command.TraceParent,
		traceState:  command.TraceState,
	}, nil, &result)
	if err != nil {
		return biz.LoanApplicationDetail{}, err
	}
	return biz.LoanApplicationDetail{
		ApplicationID: result.ApplicationID,
		Loan: biz.LoanTerms{
			Amount:  result.Loan.Amount,
			Term:    result.Loan.Term,
			Purpose: result.Loan.Purpose,
		},
		AcceptedQuote: biz.AcceptedQuote{
			QuoteID:       result.AcceptedQuote.QuoteID,
			Monthly:       result.AcceptedQuote.Monthly,
			APR:           result.AcceptedQuote.APR,
			TotalInterest: result.AcceptedQuote.TotalInterest,
			TotalPayable:  result.AcceptedQuote.TotalPayable,
			ValidUntil:    result.AcceptedQuote.ValidUntil,
		},
		Status:      result.Status,
		CurrentStep: result.CurrentStep,
	}, nil
}

func (c *OriginationClient) PatchLoanApplication(ctx context.Context, command biz.PatchLoanApplicationCommand) (biz.LoanApplicationSummary, error) {
	var result loanApplicationSummaryResponse
	err := c.doJSON(ctx, http.MethodPatch, "/api/v1/loan-applications/"+url.PathEscape(command.ApplicationID), commandHeaders{
		applicantID:    command.ApplicantID,
		idempotencyKey: command.IdempotencyKey,
		traceParent:    command.TraceParent,
		traceState:     command.TraceState,
	}, command.RawRequest, &result)
	if err != nil {
		return biz.LoanApplicationSummary{}, err
	}
	return biz.LoanApplicationSummary{
		ApplicationID: result.ApplicationID,
		Status:        result.Status,
		CurrentStep:   result.CurrentStep,
	}, nil
}

type commandHeaders struct {
	applicantID    string
	idempotencyKey string
	traceParent    string
	traceState     string
}

func (c *OriginationClient) doJSON(ctx context.Context, method string, path string, headers commandHeaders, body []byte, out any) error {
	if c == nil || c.resolver == nil {
		return originationUnavailable()
	}
	ctx, span := originationTracer.Start(ctx,
		method+" "+path,
		oteltrace.WithSpanKind(oteltrace.SpanKindClient),
		oteltrace.WithAttributes(
			attribute.String("http.request.method", method),
			attribute.String("server.address", "origination-api"),
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
		span.SetStatus(codes.Error, "resolve origination-api")
		return originationUnavailable()
	}
	endpoint, err := url.JoinPath(baseURL, path)
	if err != nil {
		span.SetStatus(codes.Error, "build origination-api url")
		return originationUnavailable()
	}
	req, err := http.NewRequestWithContext(ctx, method, endpoint, bytes.NewReader(body))
	if err != nil {
		span.SetStatus(codes.Error, "build origination-api request")
		return originationUnavailable()
	}
	if len(body) > 0 {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set(bffkit.HeaderApplicantID, headers.applicantID)
	if headers.idempotencyKey != "" {
		req.Header.Set(bffkit.HeaderIdempotencyKey, headers.idempotencyKey)
	}
	if headers.traceParent != "" {
		req.Header.Set(bffkit.HeaderTraceParent, headers.traceParent)
	}
	if headers.traceState != "" {
		req.Header.Set(bffkit.HeaderTraceState, headers.traceState)
	}

	resp, err := c.client.Do(req)
	if err != nil {
		span.SetStatus(codes.Error, "call origination-api")
		return originationUnavailable()
	}
	defer func() { _ = resp.Body.Close() }()
	span.SetAttributes(attribute.Int("http.response.status_code", resp.StatusCode))
	if resp.StatusCode == http.StatusOK {
		if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
			span.SetStatus(codes.Error, "decode origination-api response")
			return originationUnavailable()
		}
		return nil
	}
	mapped := originationErrorFromHTTP(resp)
	if mapped.Code == biz.OriginationCodeUnavailable {
		span.SetStatus(codes.Error, mapped.Code)
	}
	return mapped
}

type loanApplicationSummaryResponse struct {
	ApplicationID string `json:"applicationId"`
	Status        string `json:"status"`
	CurrentStep   string `json:"currentStep"`
}

type loanApplicationDetailResponse struct {
	ApplicationID string                    `json:"applicationId"`
	Loan          loanTermsResponse         `json:"loan"`
	AcceptedQuote acceptedQuoteHTTPResponse `json:"acceptedQuote"`
	Status        string                    `json:"status"`
	CurrentStep   string                    `json:"currentStep"`
}

type loanTermsResponse struct {
	Amount  string `json:"amount"`
	Term    int    `json:"term"`
	Purpose string `json:"purpose"`
}

type acceptedQuoteHTTPResponse struct {
	QuoteID       string `json:"quoteId"`
	Monthly       string `json:"monthly"`
	APR           string `json:"apr"`
	TotalInterest string `json:"totalInterest"`
	TotalPayable  string `json:"totalPayable"`
	ValidUntil    string `json:"validUntil"`
}

func newOriginationResolver(c *conf.Origination) ServiceResolver {
	if c != nil && strings.TrimSpace(c.HTTP.BaseURL) != "" {
		return staticURLResolver(strings.TrimRight(strings.TrimSpace(c.HTTP.BaseURL), "/"))
	}
	return NewOriginationConsulResolver(c)
}

func NewOriginationConsulResolver(c *conf.Origination) *OriginationConsulResolver {
	consul := conf.Consul{}
	if c != nil {
		consul = c.Consul
	}
	scheme := firstNonEmpty(consul.Scheme, "http")
	address := firstNonEmpty(consul.Address, "127.0.0.1:8500")
	return &OriginationConsulResolver{
		baseURL:     scheme + "://" + address,
		serviceName: firstNonEmpty(consul.ServiceName, "origination-api"),
		client:      &http.Client{Timeout: 2 * time.Second},
	}
}

type OriginationConsulResolver struct {
	baseURL     string
	serviceName string
	client      *http.Client
}

func (r *OriginationConsulResolver) Resolve(ctx context.Context) (string, error) {
	if r == nil {
		return "", errors.New("origination consul resolver is not configured")
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
	return "", errors.New("no healthy origination-api instance")
}

func originationErrorFromHTTP(resp *http.Response) *biz.OriginationError {
	code := ""
	var body struct {
		Error string `json:"error"`
		Code  string `json:"code"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err == nil {
		code = firstNonEmpty(body.Error, body.Code)
	}
	switch resp.StatusCode {
	case http.StatusBadRequest:
		if code == biz.OriginationCodeIdempotencyKeyRequired {
			return &biz.OriginationError{Code: code, Message: "idempotency key is required"}
		}
	case http.StatusForbidden:
		if code == biz.OriginationCodeForbidden {
			return &biz.OriginationError{Code: code, Message: "forbidden"}
		}
	case http.StatusNotFound:
		if code == biz.OriginationCodeNotFound {
			return &biz.OriginationError{Code: code, Message: "not found"}
		}
	case http.StatusGone:
		if code == biz.OriginationCodeQuoteExpired {
			return &biz.OriginationError{Code: code, Message: "quote expired"}
		}
	case http.StatusUnprocessableEntity:
		switch code {
		case biz.OriginationCodeAmountOutOfRange, biz.OriginationCodeValidation:
			return &biz.OriginationError{Code: code, Message: code}
		}
	}
	return originationUnavailable()
}

func originationUnavailable() *biz.OriginationError {
	return &biz.OriginationError{Code: biz.OriginationCodeUnavailable, Message: "origination-api is unavailable"}
}
