package data

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	quotev1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/quote/v1"
	"github.com/spark/bffkit"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/conf"
)

func TestQuoteClient_CreateQuoteCallsQuoteAPIOverGRPC(t *testing.T) {
	server := newQuoteGRPCTestServer(t, &fakeQuoteServer{
		createResponse: &quotev1pb.CreateQuoteResponse{
			Quote: &quotev1pb.Quote{
				QuoteId:       "quote_123",
				Monthly:       "8560.75",
				Apr:           "0.0520",
				TotalInterest: "2729.00",
				TotalPayable:  "102729.00",
				ValidUntil:    "2026-06-28T03:00:00Z",
			},
		},
	})

	client := &QuoteClient{
		resolver:    staticResolver(server.target),
		timeout:     time.Second,
		dialOptions: []grpc.DialOption{grpc.WithTransportCredentials(insecure.NewCredentials())},
	}
	ctx := bffkit.ContextWithTraceID(context.Background(), "trace-123")
	result, err := client.CreateQuote(ctx, biz.CreateQuoteCommand{
		ApplicantID: "applicant_001",
		ProductCode: "PIL",
		Amount:      []byte(`"100000.00"`),
		Term:        12,
		Purpose:     "debt_consolidation",
	})
	if err != nil {
		t.Fatalf("CreateQuote() error = %v", err)
	}
	if result.QuoteID != "quote_123" || result.Monthly != "8560.75" || result.APR != "0.0520" || result.TotalInterest != "2729.00" || result.TotalPayable != "102729.00" || result.ValidUntil == "" {
		t.Fatalf("result = %#v", result)
	}
	if server.fake.lastCreate.GetProductCode() != "PIL" || server.fake.lastCreate.GetAmount() != "100000.00" || server.fake.lastCreate.GetTerm() != 12 || server.fake.lastCreate.GetPurpose() != "debt_consolidation" {
		t.Fatalf("request = %#v", server.fake.lastCreate)
	}
	if server.fake.lastCreate.GetTraceId() != "trace-123" {
		t.Fatalf("trace_id = %q, want trace-123", server.fake.lastCreate.GetTraceId())
	}
	if got := server.fake.lastMetadata.Get("x-applicant-id"); len(got) != 1 || got[0] != "applicant_001" {
		t.Fatalf("x-applicant-id = %#v, want applicant_001", got)
	}
}

func TestQuoteClient_CreateQuoteMapsGRPCErrors(t *testing.T) {
	tests := []struct {
		name     string
		err      error
		wantCode string
	}{
		{"amount out of range", status.Error(codes.InvalidArgument, "QUOTE-PARAM-0002"), biz.PricingCodeAmountOutOfRange},
		{"validation error", status.Error(codes.InvalidArgument, "QUOTE-PARAM-0001"), biz.PricingCodeValidation},
		{"unavailable", status.Error(codes.Unavailable, "backend unavailable"), biz.PricingCodeQuoteUnavailable},
		{"deadline", status.Error(codes.DeadlineExceeded, "deadline exceeded"), biz.PricingCodeQuoteUnavailable},
		{"unknown", status.Error(codes.Internal, "internal"), biz.PricingCodeQuoteUnavailable},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := newQuoteGRPCTestServer(t, &fakeQuoteServer{createErr: tt.err})
			client := &QuoteClient{
				resolver:    staticResolver(server.target),
				timeout:     time.Second,
				dialOptions: []grpc.DialOption{grpc.WithTransportCredentials(insecure.NewCredentials())},
			}
			_, err := client.CreateQuote(context.Background(), biz.CreateQuoteCommand{ApplicantID: "applicant_001"})
			pricingErr, ok := err.(*biz.PricingError)
			if !ok {
				t.Fatalf("err = %#v, want PricingError", err)
			}
			if pricingErr.Code != tt.wantCode {
				t.Fatalf("code = %q, want %q", pricingErr.Code, tt.wantCode)
			}
		})
	}
}

func TestQuoteGRPCConsulResolver_ResolvePrefersGrpcPortMetadata(t *testing.T) {
	consul := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/health/service/quote-api" {
			t.Fatalf("path = %q", r.URL.Path)
		}
		if r.URL.Query().Get("passing") != "true" {
			t.Fatalf("passing = %q", r.URL.Query().Get("passing"))
		}
		_, _ = w.Write([]byte(`[{"Node":{"Address":"10.0.0.10"},"Service":{"Address":"quote-api.lendora-sta-quote-api.svc.cluster.local","Port":80,"Meta":{"grpc_port":"9090"}}}]`))
	}))
	defer consul.Close()

	resolver := NewQuoteGRPCConsulResolver(quoteConsulFromURL(consul.URL))
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	target, err := resolver.Resolve(ctx)
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if target != "quote-api.lendora-sta-quote-api.svc.cluster.local:9090" {
		t.Fatalf("target = %q", target)
	}
}

type quoteGRPCTestServer struct {
	target string
	fake   *fakeQuoteServer
}

func newQuoteGRPCTestServer(t *testing.T, fake *fakeQuoteServer) quoteGRPCTestServer {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := grpc.NewServer()
	quotev1pb.RegisterQuoteServiceServer(srv, fake)
	go func() {
		_ = srv.Serve(listener)
	}()
	t.Cleanup(func() {
		srv.Stop()
		_ = listener.Close()
	})
	return quoteGRPCTestServer{target: listener.Addr().String(), fake: fake}
}

type fakeQuoteServer struct {
	quotev1pb.UnimplementedQuoteServiceServer
	createResponse *quotev1pb.CreateQuoteResponse
	createErr      error
	lastCreate     *quotev1pb.CreateQuoteRequest
	lastMetadata   metadata.MD
}

func (s *fakeQuoteServer) CreateQuote(ctx context.Context, req *quotev1pb.CreateQuoteRequest) (*quotev1pb.CreateQuoteResponse, error) {
	s.lastCreate = req
	s.lastMetadata, _ = metadata.FromIncomingContext(ctx)
	if s.createErr != nil {
		return nil, s.createErr
	}
	return s.createResponse, nil
}

func quoteConsulFromURL(raw string) conf.Consul {
	consul := consulFromURL(raw)
	consul.ServiceName = "quote-api"
	return consul
}
