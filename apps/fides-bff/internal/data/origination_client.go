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
	"strings"
	"time"

	originationv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/origination/v1"
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

var originationTracer = otel.Tracer("github.com/spark/fides-bff/internal/data")

const (
	originationCodeAuth          = "ORIGINATION-AUTH-0001"
	originationCodeParam         = "ORIGINATION-PARAM-0001"
	originationCodePermission    = "ORIGINATION-PERMISSION-0001"
	originationCodeStateNotFound = "ORIGINATION-STATE-0001"
	originationCodeStateConflict = "ORIGINATION-STATE-0002"
	originationCodeQuoteNotFound = "ORIGINATION-QUOTE-0001"
	originationCodeQuoteExpired  = "ORIGINATION-QUOTE-0002"
	originationCodeQuoteDown     = "ORIGINATION-QUOTE-0003"
)

type OriginationClient struct {
	resolver    ServiceResolver
	timeout     time.Duration
	dialOptions []grpc.DialOption
}

func NewOriginationClient(c *conf.Origination) *OriginationClient {
	timeout := 3 * time.Second
	plaintext := true
	consul := conf.Consul{}
	if c != nil {
		consul = c.Consul
		if parsed, err := time.ParseDuration(c.GRPC.Timeout); err == nil && parsed > 0 {
			timeout = parsed
		}
		plaintext = c.GRPC.Plaintext
	}
	opts := []grpc.DialOption{}
	if plaintext {
		opts = append(opts, grpc.WithTransportCredentials(insecure.NewCredentials()))
	} else {
		opts = append(opts, grpc.WithTransportCredentials(credentials.NewTLS(&tls.Config{})))
	}
	return &OriginationClient{
		resolver:    NewOriginationGRPCConsulResolver(consul),
		timeout:     timeout,
		dialOptions: opts,
	}
}

func (c *OriginationClient) CreateLoanApplication(ctx context.Context, command biz.CreateLoanApplicationCommand) (biz.LoanApplicationSummary, error) {
	client, cleanup, err := c.client(ctx)
	if err != nil {
		return biz.LoanApplicationSummary{}, err
	}
	defer cleanup()
	payload := loanApplicationPayload(command.RawRequest)
	paramFallback := originationParamFallbackCode(command.IdempotencyKey, payload, true)
	rpcCtx, cancel, endSpan := c.rpcContext(ctx, command.ApplicantID, "CreateLoanApplication")
	defer cancel()
	resp, err := client.CreateLoanApplication(rpcCtx, &originationv1pb.CreateLoanApplicationRequest{
		ProductCode:    payload.ProductCode,
		QuoteId:        payload.QuoteID,
		Loan:           loanTermsRequest(payload.Loan),
		IdempotencyKey: command.IdempotencyKey,
	})
	if err != nil {
		mapped := originationErrorFromGRPC(err, paramFallback)
		endSpan(err, mapped.Code)
		return biz.LoanApplicationSummary{}, mapped
	}
	endSpan(nil, "")
	return biz.LoanApplicationSummary{
		ApplicationID: resp.GetApplicationId(),
		Status:        resp.GetStatus(),
		CurrentStep:   resp.GetCurrentStep(),
	}, nil
}

func (c *OriginationClient) GetLoanApplication(ctx context.Context, command biz.GetLoanApplicationCommand) (biz.LoanApplicationDetail, error) {
	client, cleanup, err := c.client(ctx)
	if err != nil {
		return biz.LoanApplicationDetail{}, err
	}
	defer cleanup()
	rpcCtx, cancel, endSpan := c.rpcContext(ctx, command.ApplicantID, "GetLoanApplication")
	defer cancel()
	resp, err := client.GetLoanApplication(rpcCtx, &originationv1pb.GetLoanApplicationRequest{ApplicationId: command.ApplicationID})
	if err != nil {
		mapped := originationErrorFromGRPC(err, biz.OriginationCodeValidation)
		endSpan(err, mapped.Code)
		return biz.LoanApplicationDetail{}, mapped
	}
	endSpan(nil, "")
	loan := resp.GetLoan()
	quote := resp.GetAcceptedQuote()
	return biz.LoanApplicationDetail{
		ApplicationID: resp.GetApplicationId(),
		Loan: biz.LoanTerms{
			Amount:  loan.GetAmount(),
			Term:    int(loan.GetTerm()),
			Purpose: loan.GetPurpose(),
		},
		AcceptedQuote: biz.AcceptedQuote{
			QuoteID:       quote.GetQuoteId(),
			Monthly:       quote.GetMonthly(),
			APR:           quote.GetApr(),
			TotalInterest: quote.GetTotalInterest(),
			TotalPayable:  quote.GetTotalPayable(),
			ValidUntil:    quote.GetValidUntil(),
		},
		Status:      resp.GetStatus(),
		CurrentStep: resp.GetCurrentStep(),
	}, nil
}

func (c *OriginationClient) PatchLoanApplication(ctx context.Context, command biz.PatchLoanApplicationCommand) (biz.LoanApplicationSummary, error) {
	client, cleanup, err := c.client(ctx)
	if err != nil {
		return biz.LoanApplicationSummary{}, err
	}
	defer cleanup()
	payload := loanApplicationPayload(command.RawRequest)
	paramFallback := originationParamFallbackCode(command.IdempotencyKey, payload, false)
	rpcCtx, cancel, endSpan := c.rpcContext(ctx, command.ApplicantID, "UpdateLoanApplication")
	defer cancel()
	resp, err := client.UpdateLoanApplication(rpcCtx, &originationv1pb.UpdateLoanApplicationRequest{
		ApplicationId:  command.ApplicationID,
		QuoteId:        payload.QuoteID,
		Loan:           loanTermsRequest(payload.Loan),
		IdempotencyKey: command.IdempotencyKey,
	})
	if err != nil {
		mapped := originationErrorFromGRPC(err, paramFallback)
		endSpan(err, mapped.Code)
		return biz.LoanApplicationSummary{}, mapped
	}
	endSpan(nil, "")
	return biz.LoanApplicationSummary{
		ApplicationID: resp.GetApplicationId(),
		Status:        resp.GetStatus(),
		CurrentStep:   resp.GetCurrentStep(),
	}, nil
}

func (c *OriginationClient) AdvanceApplicationStep(ctx context.Context, command biz.AdvanceApplicationStepCommand) (biz.AdvanceApplicationStepResult, error) {
	client, cleanup, err := c.client(ctx)
	if err != nil {
		return biz.AdvanceApplicationStepResult{}, identityOriginationUnavailable()
	}
	defer cleanup()
	rpcCtx, cancel, endSpan := c.rpcContext(ctx, command.ApplicantID, "AdvanceApplicationStep")
	defer cancel()
	resp, err := client.AdvanceApplicationStep(rpcCtx, &originationv1pb.OriginationLoanApplicationServiceAdvanceApplicationStepRequest{
		ApplicationId: command.ApplicationID,
		TargetStep:    originationv1pb.ApplicationStep_APPLICATION_STEP_IDENTITY_INFORMATION,
	})
	if err != nil {
		mapped := draftErrorFromGRPC(err, command.ApplicationID)
		endSpan(err, identityProfileErrorCode(mapped))
		return biz.AdvanceApplicationStepResult{}, mapped
	}
	endSpan(nil, "")
	return biz.AdvanceApplicationStepResult{
		ApplicationID: resp.GetApplicationId(),
		CurrentStep:   originationStepValue(resp.GetCurrentStep()),
	}, nil
}

func (c *OriginationClient) client(ctx context.Context) (originationv1pb.OriginationLoanApplicationServiceClient, func(), error) {
	if c == nil || c.resolver == nil {
		return nil, func() {}, originationUnavailable()
	}
	target, err := c.resolver.Resolve(ctx)
	if err != nil {
		return nil, func() {}, originationUnavailable()
	}
	conn, err := grpcNewClient(target, c.dialOptions...)
	if err != nil {
		return nil, func() {}, originationUnavailable()
	}
	return originationv1pb.NewOriginationLoanApplicationServiceClient(conn), func() { _ = conn.Close() }, nil
}

func (c *OriginationClient) rpcContext(ctx context.Context, applicantID string, method string) (context.Context, context.CancelFunc, func(error, string)) {
	ctx, span := originationTracer.Start(ctx,
		"OriginationLoanApplicationService/"+method,
		oteltrace.WithSpanKind(oteltrace.SpanKindClient),
		oteltrace.WithAttributes(
			attribute.String("rpc.system", "grpc"),
			attribute.String("rpc.service", "vesta.lendora.origination.v1.OriginationLoanApplicationService"),
			attribute.String("rpc.method", method),
		),
	)
	ctx = bffkit.ContextWithPrincipal(ctx, bffkit.Principal{ApplicantID: applicantID})
	ctx = bffkit.OutgoingGRPCContext(ctx)
	endSpan := func(err error, errorCode string) {
		if err != nil {
			span.SetStatus(codes.Error, status.Code(err).String())
			span.SetAttributes(attribute.String("rpc.grpc.status_code", status.Code(err).String()))
			if errorCode != "" {
				span.SetAttributes(attribute.String("error_code", errorCode))
			}
		}
		span.End()
	}
	if c == nil || c.timeout <= 0 {
		return ctx, func() {}, endSpan
	}
	timeoutCtx, cancel := context.WithTimeout(ctx, c.timeout)
	return timeoutCtx, cancel, endSpan
}

type loanApplicationRequestPayload struct {
	ProductCode string        `json:"productCode"`
	QuoteID     string        `json:"quoteId"`
	Loan        biz.LoanTerms `json:"loan"`
}

func loanApplicationPayload(raw json.RawMessage) loanApplicationRequestPayload {
	var payload loanApplicationRequestPayload
	if err := json.Unmarshal(raw, &payload); err != nil {
		return loanApplicationRequestPayload{}
	}
	return payload
}

func loanTermsRequest(loan biz.LoanTerms) *originationv1pb.LoanTerms {
	if loan.Amount == "" && loan.Term == 0 && loan.Purpose == "" {
		return nil
	}
	return &originationv1pb.LoanTerms{
		Amount:  loan.Amount,
		Term:    int32(loan.Term),
		Purpose: loan.Purpose,
	}
}

func originationParamFallbackCode(idempotencyKey string, payload loanApplicationRequestPayload, requireProductCode bool) string {
	if strings.TrimSpace(idempotencyKey) == "" {
		return biz.OriginationCodeIdempotencyKeyRequired
	}
	if requireProductCode && strings.TrimSpace(payload.ProductCode) == "" {
		return biz.OriginationCodeValidation
	}
	return biz.OriginationCodeValidation
}

func NewOriginationGRPCConsulResolver(consul conf.Consul) *OriginationGRPCConsulResolver {
	scheme := firstNonEmpty(consul.Scheme, "http")
	address := firstNonEmpty(consul.Address, "127.0.0.1:8500")
	return &OriginationGRPCConsulResolver{
		baseURL:     scheme + "://" + address,
		serviceName: firstNonEmpty(consul.ServiceName, "origination-api"),
		client:      &http.Client{Timeout: 2 * time.Second},
	}
}

type OriginationGRPCConsulResolver struct {
	baseURL     string
	serviceName string
	client      *http.Client
}

func (r *OriginationGRPCConsulResolver) Resolve(ctx context.Context) (string, error) {
	if r == nil {
		return "", errors.New("origination grpc consul resolver is not configured")
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
	return "", errors.New("no healthy origination-api grpc instance")
}

func originationErrorFromGRPC(err error, paramFallbackCode string) *biz.OriginationError {
	st, ok := status.FromError(err)
	if !ok {
		return originationUnavailable()
	}
	message := st.Message()
	switch st.Code() {
	case grpccodes.Unavailable, grpccodes.DeadlineExceeded:
		return originationUnavailable()
	case grpccodes.InvalidArgument:
		switch message {
		case originationCodeParam:
			switch paramFallbackCode {
			case biz.OriginationCodeIdempotencyKeyRequired:
				return &biz.OriginationError{Code: biz.OriginationCodeIdempotencyKeyRequired, Message: "idempotency key is required"}
			case biz.OriginationCodeAmountOutOfRange:
				return &biz.OriginationError{Code: biz.OriginationCodeAmountOutOfRange, Message: "amount out of range"}
			default:
				return &biz.OriginationError{Code: biz.OriginationCodeValidation, Message: "validation failed"}
			}
		case biz.OriginationCodeIdempotencyKeyRequired:
			return &biz.OriginationError{Code: biz.OriginationCodeIdempotencyKeyRequired, Message: "idempotency key is required"}
		case biz.OriginationCodeAmountOutOfRange:
			return &biz.OriginationError{Code: biz.OriginationCodeAmountOutOfRange, Message: "amount out of range"}
		case biz.OriginationCodeValidation:
			return &biz.OriginationError{Code: biz.OriginationCodeValidation, Message: "validation failed"}
		}
	case grpccodes.Unauthenticated, grpccodes.PermissionDenied:
		return &biz.OriginationError{Code: biz.OriginationCodeForbidden, Message: "forbidden"}
	case grpccodes.NotFound:
		return &biz.OriginationError{Code: biz.OriginationCodeNotFound, Message: "not found"}
	case grpccodes.AlreadyExists:
		if message == originationCodeStateConflict {
			return &biz.OriginationError{Code: biz.OriginationCodeValidation, Message: "validation failed"}
		}
	case grpccodes.FailedPrecondition:
		if message == originationCodeQuoteExpired || message == biz.OriginationCodeQuoteExpired {
			return &biz.OriginationError{Code: biz.OriginationCodeQuoteExpired, Message: "quote expired"}
		}
	}
	return originationUnavailable()
}

func originationUnavailable() *biz.OriginationError {
	return &biz.OriginationError{Code: biz.OriginationCodeUnavailable, Message: "origination-api is unavailable"}
}

func draftErrorFromGRPC(err error, applicationID string) error {
	st, ok := status.FromError(err)
	if !ok {
		return identityOriginationUnavailable()
	}
	switch st.Code() {
	case grpccodes.Unavailable, grpccodes.DeadlineExceeded:
		return identityOriginationUnavailable()
	case grpccodes.Unauthenticated, grpccodes.PermissionDenied:
		return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeForbidden, Message: "forbidden"}
	case grpccodes.InvalidArgument:
		if st.Message() == biz.IdentityProfileCodeApplicationRequired || (st.Message() == originationCodeParam && strings.TrimSpace(applicationID) == "") {
			return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeApplicationRequired, Message: "applicationId is required"}
		}
		return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeValidation, Message: "validation failed"}
	}
	return identityOriginationUnavailable()
}

func identityOriginationUnavailable() *biz.IdentityProfileError {
	return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeOriginationUnavailable, Message: "origination-api is unavailable"}
}

func identityProfileErrorCode(err error) string {
	if profileErr, ok := err.(*biz.IdentityProfileError); ok {
		return profileErr.Code
	}
	return ""
}

func originationStepValue(value originationv1pb.ApplicationStep) string {
	switch value {
	case originationv1pb.ApplicationStep_APPLICATION_STEP_IDENTITY_INFORMATION:
		return "identity_information"
	case originationv1pb.ApplicationStep_APPLICATION_STEP_LOAN_REQUEST:
		return "loan_request"
	default:
		return ""
	}
}
