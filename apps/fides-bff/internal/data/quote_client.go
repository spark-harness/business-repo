package data

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"time"

	quotev1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/quote/v1"
	"github.com/spark/bffkit"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	oteltrace "go.opentelemetry.io/otel/trace"
	"google.golang.org/grpc"
	grpccodes "google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/conf"
)

var quoteTracer = otel.Tracer("github.com/spark/fides-bff/internal/data")

const (
	quoteCodeAmountOutOfRange = "QUOTE-PARAM-0002"
	quoteCodeValidation       = "QUOTE-PARAM-0001"
)

type QuoteClient struct {
	resolver    ServiceResolver
	timeout     time.Duration
	dialOptions []grpc.DialOption
}

func NewQuoteClient(c *conf.Quote) *QuoteClient {
	timeout := 3 * time.Second
	plaintext := true
	consul := conf.Consul{}
	target := ""
	if c != nil {
		consul = c.Consul
		if parsed, err := time.ParseDuration(c.GRPC.Timeout); err == nil && parsed > 0 {
			timeout = parsed
		}
		plaintext = c.GRPC.Plaintext
		target = c.GRPC.Target
	}
	opts := []grpc.DialOption{}
	if plaintext {
		opts = append(opts, grpc.WithTransportCredentials(insecure.NewCredentials()))
	} else {
		opts = append(opts, grpc.WithTransportCredentials(credentials.NewTLS(&tls.Config{})))
	}
	return &QuoteClient{
		resolver:    grpcResolver(target, NewQuoteGRPCConsulResolver(consul)),
		timeout:     timeout,
		dialOptions: opts,
	}
}

func (c *QuoteClient) CreateQuote(ctx context.Context, command biz.CreateQuoteCommand) (biz.QuoteResult, error) {
	client, cleanup, err := c.client(ctx)
	if err != nil {
		return biz.QuoteResult{}, err
	}
	defer cleanup()

	rpcCtx, cancel, endSpan := c.rpcContext(ctx, command, "CreateQuote")
	defer cancel()
	resp, err := client.CreateQuote(rpcCtx, &quotev1pb.CreateQuoteRequest{
		ProductCode: command.ProductCode,
		Amount:      quoteAmount(command.Amount),
		Term:        int32(command.Term),
		Purpose:     command.Purpose,
		TraceId:     quoteTraceID(ctx),
	})
	endSpan(err)
	if err != nil {
		return biz.QuoteResult{}, quoteErrorFromGRPC(err)
	}
	quote := resp.GetQuote()
	if quote == nil {
		return biz.QuoteResult{}, quoteUnavailable()
	}
	return biz.QuoteResult{
		QuoteID:       quote.GetQuoteId(),
		Monthly:       quote.GetMonthly(),
		APR:           quote.GetApr(),
		TotalInterest: quote.GetTotalInterest(),
		TotalPayable:  quote.GetTotalPayable(),
		ValidUntil:    quote.GetValidUntil(),
	}, nil
}

func (c *QuoteClient) client(ctx context.Context) (quotev1pb.QuoteServiceClient, func(), error) {
	if c == nil || c.resolver == nil {
		return nil, func() {}, quoteUnavailable()
	}
	target, err := c.resolver.Resolve(ctx)
	if err != nil {
		return nil, func() {}, quoteUnavailable()
	}
	conn, err := grpcNewClient(target, c.dialOptions...)
	if err != nil {
		return nil, func() {}, quoteUnavailable()
	}
	return quotev1pb.NewQuoteServiceClient(conn), func() { _ = conn.Close() }, nil
}

func (c *QuoteClient) rpcContext(ctx context.Context, command biz.CreateQuoteCommand, method string) (context.Context, context.CancelFunc, func(error)) {
	ctx, span := quoteTracer.Start(ctx,
		"QuoteService/"+method,
		oteltrace.WithSpanKind(oteltrace.SpanKindClient),
		oteltrace.WithAttributes(
			attribute.String("rpc.system", "grpc"),
			attribute.String("rpc.service", "vesta.lendora.quote.v1.QuoteService"),
			attribute.String("rpc.method", method),
		),
	)
	ctx = bffkit.ContextWithPrincipal(ctx, bffkit.Principal{ApplicantID: command.ApplicantID})
	ctx = bffkit.OutgoingGRPCContext(ctx)
	endSpan := func(err error) {
		if err != nil {
			span.SetStatus(codes.Error, status.Code(err).String())
			span.SetAttributes(attribute.String("rpc.grpc.status_code", status.Code(err).String()))
		}
		span.End()
	}
	if c == nil || c.timeout <= 0 {
		return ctx, func() {}, endSpan
	}
	timeoutCtx, cancel := context.WithTimeout(ctx, c.timeout)
	return timeoutCtx, cancel, endSpan
}

func quoteAmount(raw json.RawMessage) string {
	var value string
	if err := json.Unmarshal(raw, &value); err == nil {
		return value
	}
	return ""
}

func quoteTraceID(ctx context.Context) string {
	traceID, _ := bffkit.TraceIDFromContext(ctx)
	return traceID
}

type QuoteGRPCConsulResolver struct {
	baseURL     string
	serviceName string
	client      *http.Client
}

func NewQuoteGRPCConsulResolver(consul conf.Consul) *QuoteGRPCConsulResolver {
	scheme := firstNonEmpty(consul.Scheme, "http")
	address := firstNonEmpty(consul.Address, "127.0.0.1:8500")
	return &QuoteGRPCConsulResolver{
		baseURL:     scheme + "://" + address,
		serviceName: firstNonEmpty(consul.ServiceName, "quote-api"),
		client:      &http.Client{Timeout: 2 * time.Second},
	}
}

func (r *QuoteGRPCConsulResolver) Resolve(ctx context.Context) (string, error) {
	if r == nil {
		return "", errors.New("quote grpc consul resolver is not configured")
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
		port := entry.Service.grpcPort()
		if address == "" || port == 0 {
			continue
		}
		return net.JoinHostPort(address, strconv.Itoa(port)), nil
	}
	return "", errors.New("no healthy quote-api grpc instance")
}

func quoteErrorFromGRPC(err error) *biz.PricingError {
	st, ok := status.FromError(err)
	if !ok {
		return quoteUnavailable()
	}
	switch st.Code() {
	case grpccodes.Unavailable, grpccodes.DeadlineExceeded:
		return quoteUnavailable()
	case grpccodes.InvalidArgument:
		switch st.Message() {
		case quoteCodeAmountOutOfRange:
			return &biz.PricingError{Code: biz.PricingCodeAmountOutOfRange, Message: "amount out of range"}
		case quoteCodeValidation:
			return &biz.PricingError{Code: biz.PricingCodeValidation, Message: "validation failed"}
		}
	}
	return quoteUnavailable()
}

func quoteUnavailable() *biz.PricingError {
	return &biz.PricingError{Code: biz.PricingCodeQuoteUnavailable, Message: "quote-api is unavailable"}
}
