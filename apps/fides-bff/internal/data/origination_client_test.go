package data

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	originationv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/origination/v1"
	"github.com/spark/bffkit"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/conf"
)

func TestOriginationClient_CreateCallsOriginationAPIOverGRPC(t *testing.T) {
	server := newOriginationGRPCTestServer(t, &fakeOriginationServer{
		createResponse: &originationv1pb.CreateLoanApplicationResponse{
			ApplicationId: "app_123",
			Status:        "draft",
			CurrentStep:   "loan_request",
		},
	})

	client := newTestOriginationClient(server.target)
	ctx := bffkit.ContextWithTraceID(context.Background(), "trace-123")
	result, err := client.CreateLoanApplication(ctx, biz.CreateLoanApplicationCommand{
		ApplicantID:    "applicant_001",
		IdempotencyKey: "idem-create",
		RawRequest:     json.RawMessage(`{"productCode":"PIL","quoteId":"quote_123","loan":{"amount":"100000.00","term":12,"purpose":"debt_consolidation"}}`),
	})
	if err != nil {
		t.Fatalf("CreateLoanApplication() error = %v", err)
	}
	if result.ApplicationID != "app_123" || result.Status != "draft" || result.CurrentStep != "loan_request" {
		t.Fatalf("result = %#v", result)
	}
	if server.fake.lastCreate.GetProductCode() != "PIL" || server.fake.lastCreate.GetQuoteId() != "quote_123" || server.fake.lastCreate.GetLoan().GetAmount() != "100000.00" || server.fake.lastCreate.GetLoan().GetTerm() != 12 || server.fake.lastCreate.GetLoan().GetPurpose() != "debt_consolidation" {
		t.Fatalf("request = %#v", server.fake.lastCreate)
	}
	if server.fake.lastCreate.GetIdempotencyKey() != "idem-create" {
		t.Fatalf("idempotency_key = %q, want idem-create", server.fake.lastCreate.GetIdempotencyKey())
	}
	if got := server.fake.lastMetadata.Get("x-applicant-id"); len(got) != 1 || got[0] != "applicant_001" {
		t.Fatalf("x-applicant-id = %#v, want applicant_001", got)
	}
	if got := server.fake.lastMetadata.Get("x-trace-id"); len(got) != 1 || got[0] != "trace-123" {
		t.Fatalf("x-trace-id = %#v, want trace-123", got)
	}
}

func TestOriginationClient_UsesConfiguredGRPCTargetWithoutConsul(t *testing.T) {
	server := newOriginationGRPCTestServer(t, &fakeOriginationServer{
		createResponse: &originationv1pb.CreateLoanApplicationResponse{
			ApplicationId: "app_direct",
			Status:        "draft",
			CurrentStep:   "loan_request",
		},
	})

	client := NewOriginationClient(&conf.Origination{
		Consul: conf.Consul{Address: "127.0.0.1:1", ServiceName: "missing-origination-api"},
		GRPC:   conf.GRPC{Target: server.target, Timeout: "1s", Plaintext: true},
	})
	result, err := client.CreateLoanApplication(context.Background(), biz.CreateLoanApplicationCommand{
		ApplicantID:    "applicant_001",
		IdempotencyKey: "idem-direct",
		RawRequest:     json.RawMessage(`{"productCode":"PIL","quoteId":"quote_123","loan":{"amount":"100000.00","term":12,"purpose":"debt_consolidation"}}`),
	})
	if err != nil {
		t.Fatalf("CreateLoanApplication() error = %v", err)
	}
	if result.ApplicationID != "app_direct" {
		t.Fatalf("application id = %q", result.ApplicationID)
	}
	if server.fake.lastCreate.GetIdempotencyKey() != "idem-direct" {
		t.Fatalf("request = %#v", server.fake.lastCreate)
	}
}

func TestOriginationClient_GetReturnsDetailOverGRPC(t *testing.T) {
	server := newOriginationGRPCTestServer(t, &fakeOriginationServer{
		getResponse: &originationv1pb.GetLoanApplicationResponse{
			ApplicationId: "app_123",
			Loan: &originationv1pb.LoanTerms{
				Amount:  "100000.00",
				Term:    12,
				Purpose: "debt_consolidation",
			},
			AcceptedQuote: &originationv1pb.AcceptedQuote{
				QuoteId:       "quote_123",
				Monthly:       "8560.75",
				Apr:           "0.0520",
				TotalInterest: "2729.00",
				TotalPayable:  "102729.00",
				ValidUntil:    "2026-06-28T03:00:00Z",
			},
			Status:      "draft",
			CurrentStep: "loan_request",
		},
	})

	client := newTestOriginationClient(server.target)
	result, err := client.GetLoanApplication(context.Background(), biz.GetLoanApplicationCommand{
		ApplicantID:   "applicant_001",
		ApplicationID: "app_123",
	})
	if err != nil {
		t.Fatalf("GetLoanApplication() error = %v", err)
	}
	if result.ApplicationID != "app_123" || result.Loan.Amount != "100000.00" || result.AcceptedQuote.QuoteID != "quote_123" {
		t.Fatalf("result = %#v", result)
	}
	if server.fake.lastGet.GetApplicationId() != "app_123" {
		t.Fatalf("request = %#v", server.fake.lastGet)
	}
}

func TestOriginationClient_PatchCallsOriginationAPIOverGRPC(t *testing.T) {
	server := newOriginationGRPCTestServer(t, &fakeOriginationServer{
		updateResponse: &originationv1pb.UpdateLoanApplicationResponse{
			ApplicationId: "app_123",
			Status:        "draft",
			CurrentStep:   "loan_request",
		},
	})

	client := newTestOriginationClient(server.target)
	result, err := client.PatchLoanApplication(context.Background(), biz.PatchLoanApplicationCommand{
		ApplicantID:    "applicant_001",
		ApplicationID:  "app_123",
		IdempotencyKey: "idem-patch",
		RawRequest:     json.RawMessage(`{"quoteId":"quote_456","loan":{"amount":"120000.00","term":24,"purpose":"home_improvement"}}`),
	})
	if err != nil {
		t.Fatalf("PatchLoanApplication() error = %v", err)
	}
	if result.ApplicationID != "app_123" || result.Status != "draft" || result.CurrentStep != "loan_request" {
		t.Fatalf("result = %#v", result)
	}
	if server.fake.lastUpdate.GetApplicationId() != "app_123" || server.fake.lastUpdate.GetQuoteId() != "quote_456" || server.fake.lastUpdate.GetLoan().GetAmount() != "120000.00" || server.fake.lastUpdate.GetLoan().GetTerm() != 24 || server.fake.lastUpdate.GetLoan().GetPurpose() != "home_improvement" {
		t.Fatalf("request = %#v", server.fake.lastUpdate)
	}
	if server.fake.lastUpdate.GetIdempotencyKey() != "idem-patch" {
		t.Fatalf("idempotency_key = %q, want idem-patch", server.fake.lastUpdate.GetIdempotencyKey())
	}
}

func TestOriginationClient_AdvanceApplicationStepOverGRPC(t *testing.T) {
	server := newOriginationGRPCTestServer(t, &fakeOriginationServer{
		advanceResponse: &originationv1pb.OriginationLoanApplicationServiceAdvanceApplicationStepResponse{
			ApplicationId: "app_123",
			CurrentStep:   originationv1pb.ApplicationStep_APPLICATION_STEP_IDENTITY_INFORMATION,
		},
	})

	client := newTestOriginationClient(server.target)
	ctx := bffkit.ContextWithTraceID(context.Background(), "trace-advance")
	result, err := client.AdvanceApplicationStep(ctx, biz.AdvanceApplicationStepCommand{
		ApplicantID:   "applicant_001",
		ApplicationID: "app_123",
	})
	if err != nil {
		t.Fatalf("AdvanceApplicationStep() error = %v", err)
	}
	if result.ApplicationID != "app_123" || result.CurrentStep != "identity_information" {
		t.Fatalf("result = %#v", result)
	}
	if server.fake.lastAdvance.GetApplicationId() != "app_123" || server.fake.lastAdvance.GetTargetStep() != originationv1pb.ApplicationStep_APPLICATION_STEP_IDENTITY_INFORMATION {
		t.Fatalf("request = %#v", server.fake.lastAdvance)
	}
	if got := server.fake.lastAdvanceMetadata.Get("x-applicant-id"); len(got) != 1 || got[0] != "applicant_001" {
		t.Fatalf("x-applicant-id = %#v, want applicant_001", got)
	}
	if got := server.fake.lastAdvanceMetadata.Get("x-trace-id"); len(got) != 1 || got[0] != "trace-advance" {
		t.Fatalf("x-trace-id = %#v, want trace-advance", got)
	}
}

func TestOriginationClient_MapsOriginationGRPCErrors(t *testing.T) {
	tests := []struct {
		command  biz.CreateLoanApplicationCommand
		name     string
		err      error
		wantCode string
	}{
		{
			name:     "idempotency required",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", RawRequest: json.RawMessage(`{}`)},
			err:      status.Error(codes.InvalidArgument, originationCodeParam),
			wantCode: biz.OriginationCodeIdempotencyKeyRequired,
		},
		{
			name:     "formal parameter validation",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", IdempotencyKey: "idem-create", RawRequest: json.RawMessage(`{}`)},
			err:      status.Error(codes.InvalidArgument, originationCodeParam),
			wantCode: biz.OriginationCodeValidation,
		},
		{
			name:     "formal parameter complete request validation",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", IdempotencyKey: "idem-create", RawRequest: json.RawMessage(`{"productCode":"PIL","quoteId":"quote_123","loan":{"amount":"120000.00","term":24,"purpose":"home_improvement"}}`)},
			err:      status.Error(codes.InvalidArgument, originationCodeParam),
			wantCode: biz.OriginationCodeValidation,
		},
		{
			name:     "legacy amount out of range",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", IdempotencyKey: "idem-create", RawRequest: json.RawMessage(`{}`)},
			err:      status.Error(codes.InvalidArgument, biz.OriginationCodeAmountOutOfRange),
			wantCode: biz.OriginationCodeAmountOutOfRange,
		},
		{
			name:     "forbidden",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", IdempotencyKey: "idem-create", RawRequest: json.RawMessage(`{}`)},
			err:      status.Error(codes.PermissionDenied, originationCodePermission),
			wantCode: biz.OriginationCodeForbidden,
		},
		{
			name:     "unauthenticated",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", IdempotencyKey: "idem-create", RawRequest: json.RawMessage(`{}`)},
			err:      status.Error(codes.Unauthenticated, originationCodeAuth),
			wantCode: biz.OriginationCodeForbidden,
		},
		{
			name:     "application not found",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", IdempotencyKey: "idem-create", RawRequest: json.RawMessage(`{}`)},
			err:      status.Error(codes.NotFound, originationCodeStateNotFound),
			wantCode: biz.OriginationCodeNotFound,
		},
		{
			name:     "quote not found",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", IdempotencyKey: "idem-create", RawRequest: json.RawMessage(`{}`)},
			err:      status.Error(codes.NotFound, originationCodeQuoteNotFound),
			wantCode: biz.OriginationCodeNotFound,
		},
		{
			name:     "quote expired",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", IdempotencyKey: "idem-create", RawRequest: json.RawMessage(`{}`)},
			err:      status.Error(codes.FailedPrecondition, originationCodeQuoteExpired),
			wantCode: biz.OriginationCodeQuoteExpired,
		},
		{
			name:     "idempotency conflict",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", IdempotencyKey: "idem-create", RawRequest: json.RawMessage(`{}`)},
			err:      status.Error(codes.AlreadyExists, originationCodeStateConflict),
			wantCode: biz.OriginationCodeValidation,
		},
		{
			name:     "quote unavailable",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", IdempotencyKey: "idem-create", RawRequest: json.RawMessage(`{}`)},
			err:      status.Error(codes.Unavailable, originationCodeQuoteDown),
			wantCode: biz.OriginationCodeUnavailable,
		},
		{
			name:     "deadline",
			command:  biz.CreateLoanApplicationCommand{ApplicantID: "applicant_001", IdempotencyKey: "idem-create", RawRequest: json.RawMessage(`{}`)},
			err:      status.Error(codes.DeadlineExceeded, "deadline exceeded"),
			wantCode: biz.OriginationCodeUnavailable,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := newOriginationGRPCTestServer(t, &fakeOriginationServer{createErr: tt.err})
			client := newTestOriginationClient(server.target)
			_, err := client.CreateLoanApplication(context.Background(), tt.command)
			originationErr, ok := err.(*biz.OriginationError)
			if !ok {
				t.Fatalf("err = %#v, want OriginationError", err)
			}
			if originationErr.Code != tt.wantCode {
				t.Fatalf("code = %q, want %q", originationErr.Code, tt.wantCode)
			}
		})
	}
}

func TestOriginationClient_MapsAdvanceApplicationStepGRPCErrors(t *testing.T) {
	tests := []struct {
		name     string
		command  biz.AdvanceApplicationStepCommand
		err      error
		wantCode string
	}{
		{
			name:     "legacy application required",
			command:  biz.AdvanceApplicationStepCommand{ApplicantID: "applicant_001", ApplicationID: "app_123"},
			err:      status.Error(codes.InvalidArgument, biz.IdentityProfileCodeApplicationRequired),
			wantCode: biz.IdentityProfileCodeApplicationRequired,
		},
		{
			name:     "formal application required",
			command:  biz.AdvanceApplicationStepCommand{ApplicantID: "applicant_001"},
			err:      status.Error(codes.InvalidArgument, originationCodeParam),
			wantCode: biz.IdentityProfileCodeApplicationRequired,
		},
		{
			name:     "formal validation",
			command:  biz.AdvanceApplicationStepCommand{ApplicantID: "applicant_001", ApplicationID: "app_123"},
			err:      status.Error(codes.InvalidArgument, originationCodeParam),
			wantCode: biz.IdentityProfileCodeValidation,
		},
		{
			name:     "forbidden",
			command:  biz.AdvanceApplicationStepCommand{ApplicantID: "applicant_001", ApplicationID: "app_123"},
			err:      status.Error(codes.PermissionDenied, originationCodePermission),
			wantCode: biz.IdentityProfileCodeForbidden,
		},
		{
			name:     "unauthenticated",
			command:  biz.AdvanceApplicationStepCommand{ApplicantID: "applicant_001", ApplicationID: "app_123"},
			err:      status.Error(codes.Unauthenticated, originationCodeAuth),
			wantCode: biz.IdentityProfileCodeForbidden,
		},
		{
			name:     "unavailable",
			command:  biz.AdvanceApplicationStepCommand{ApplicantID: "applicant_001", ApplicationID: "app_123"},
			err:      status.Error(codes.Unavailable, "backend unavailable"),
			wantCode: biz.IdentityProfileCodeOriginationUnavailable,
		},
		{
			name:     "deadline",
			command:  biz.AdvanceApplicationStepCommand{ApplicantID: "applicant_001", ApplicationID: "app_123"},
			err:      status.Error(codes.DeadlineExceeded, "deadline exceeded"),
			wantCode: biz.IdentityProfileCodeOriginationUnavailable,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := newOriginationGRPCTestServer(t, &fakeOriginationServer{advanceErr: tt.err})
			client := newTestOriginationClient(server.target)
			_, err := client.AdvanceApplicationStep(context.Background(), tt.command)
			profileErr, ok := err.(*biz.IdentityProfileError)
			if !ok {
				t.Fatalf("err = %#v, want IdentityProfileError", err)
			}
			if profileErr.Code != tt.wantCode {
				t.Fatalf("code = %q, want %q", profileErr.Code, tt.wantCode)
			}
		})
	}
}

func TestOriginationGRPCConsulResolver_ResolvePrefersGrpcPortMetadata(t *testing.T) {
	consul := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/health/service/origination-api" {
			t.Fatalf("path = %q", r.URL.Path)
		}
		if r.URL.Query().Get("passing") != "true" {
			t.Fatalf("passing = %q", r.URL.Query().Get("passing"))
		}
		_, _ = w.Write([]byte(`[{"Node":{"Address":"10.0.0.10"},"Service":{"Address":"origination-api.lendora-sta-origination-api.svc.cluster.local","Port":80,"Meta":{"grpc_port":"9001"}}}]`))
	}))
	defer consul.Close()

	resolver := NewOriginationGRPCConsulResolver(originationConsulFromURL(consul.URL))
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	target, err := resolver.Resolve(ctx)
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if target != "origination-api.lendora-sta-origination-api.svc.cluster.local:9001" {
		t.Fatalf("target = %q", target)
	}
}

type originationGRPCTestServer struct {
	target string
	fake   *fakeOriginationServer
}

func newOriginationGRPCTestServer(t *testing.T, fake *fakeOriginationServer) originationGRPCTestServer {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := grpc.NewServer()
	originationv1pb.RegisterOriginationLoanApplicationServiceServer(srv, fake)
	go func() {
		_ = srv.Serve(listener)
	}()
	t.Cleanup(func() {
		srv.Stop()
		_ = listener.Close()
	})
	return originationGRPCTestServer{target: listener.Addr().String(), fake: fake}
}

func newTestOriginationClient(target string) *OriginationClient {
	return &OriginationClient{
		resolver:    staticResolver(target),
		timeout:     time.Second,
		dialOptions: testDialOptions(),
	}
}

type fakeOriginationServer struct {
	originationv1pb.UnimplementedOriginationLoanApplicationServiceServer
	createResponse      *originationv1pb.CreateLoanApplicationResponse
	getResponse         *originationv1pb.GetLoanApplicationResponse
	updateResponse      *originationv1pb.UpdateLoanApplicationResponse
	advanceResponse     *originationv1pb.OriginationLoanApplicationServiceAdvanceApplicationStepResponse
	createErr           error
	advanceErr          error
	lastCreate          *originationv1pb.CreateLoanApplicationRequest
	lastGet             *originationv1pb.GetLoanApplicationRequest
	lastUpdate          *originationv1pb.UpdateLoanApplicationRequest
	lastAdvance         *originationv1pb.OriginationLoanApplicationServiceAdvanceApplicationStepRequest
	lastMetadata        metadata.MD
	lastAdvanceMetadata metadata.MD
}

func (s *fakeOriginationServer) CreateLoanApplication(ctx context.Context, req *originationv1pb.CreateLoanApplicationRequest) (*originationv1pb.CreateLoanApplicationResponse, error) {
	s.lastCreate = req
	s.lastMetadata, _ = metadata.FromIncomingContext(ctx)
	if s.createErr != nil {
		return nil, s.createErr
	}
	return s.createResponse, nil
}

func (s *fakeOriginationServer) GetLoanApplication(_ context.Context, req *originationv1pb.GetLoanApplicationRequest) (*originationv1pb.GetLoanApplicationResponse, error) {
	s.lastGet = req
	return s.getResponse, nil
}

func (s *fakeOriginationServer) UpdateLoanApplication(_ context.Context, req *originationv1pb.UpdateLoanApplicationRequest) (*originationv1pb.UpdateLoanApplicationResponse, error) {
	s.lastUpdate = req
	return s.updateResponse, nil
}

func (s *fakeOriginationServer) AdvanceApplicationStep(ctx context.Context, req *originationv1pb.OriginationLoanApplicationServiceAdvanceApplicationStepRequest) (*originationv1pb.OriginationLoanApplicationServiceAdvanceApplicationStepResponse, error) {
	s.lastAdvance = req
	s.lastAdvanceMetadata, _ = metadata.FromIncomingContext(ctx)
	if s.advanceErr != nil {
		return nil, s.advanceErr
	}
	return s.advanceResponse, nil
}

func originationConsulFromURL(raw string) conf.Consul {
	consul := consulFromURL(raw)
	consul.ServiceName = "origination-api"
	return consul
}
