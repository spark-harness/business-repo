package service

import (
	"context"
	"encoding/json"
	"errors"
	nethttp "net/http"

	fidesbffv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/fides-bff/v1"
	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/biz"
)

type OriginationService struct {
	uc *biz.OriginationUsecase
}

func NewOriginationService(uc *biz.OriginationUsecase) *OriginationService {
	return &OriginationService{uc: uc}
}

func (s *OriginationService) CreateLoanApplication(ctx context.Context, req *fidesbffv1pb.FidesBffLoanApplicationServiceCreateLoanApplicationRequest) (*fidesbffv1pb.FidesBffLoanApplicationServiceCreateLoanApplicationResponse, error) {
	principal, ok := bffkit.PrincipalFromContext(ctx)
	if !ok {
		return nil, bffkit.UnauthorizedError()
	}
	headers := requestHeaders(ctx)
	raw, err := marshalCreateLoanApplicationRequest(req)
	if err != nil {
		return nil, err
	}
	result, err := s.uc.CreateLoanApplication(ctx, biz.CreateLoanApplicationCommand{
		ApplicantID:    principal.ApplicantID,
		IdempotencyKey: headers.Get(bffkit.HeaderIdempotencyKey),
		TraceParent:    headers.Get(bffkit.HeaderTraceParent),
		TraceState:     headers.Get(bffkit.HeaderTraceState),
		RawRequest:     raw,
	})
	if err != nil {
		return nil, originationHTTPError(err)
	}
	return mapCreateLoanApplicationResponse(result), nil
}

func (s *OriginationService) GetLoanApplication(ctx context.Context, req *fidesbffv1pb.FidesBffLoanApplicationServiceGetLoanApplicationRequest) (*fidesbffv1pb.FidesBffLoanApplicationServiceGetLoanApplicationResponse, error) {
	principal, ok := bffkit.PrincipalFromContext(ctx)
	if !ok {
		return nil, bffkit.UnauthorizedError()
	}
	headers := requestHeaders(ctx)
	result, err := s.uc.GetLoanApplication(ctx, biz.GetLoanApplicationCommand{
		ApplicantID:   principal.ApplicantID,
		ApplicationID: req.GetApplicationId(),
		TraceParent:   headers.Get(bffkit.HeaderTraceParent),
		TraceState:    headers.Get(bffkit.HeaderTraceState),
	})
	if err != nil {
		return nil, originationHTTPError(err)
	}
	return mapGetLoanApplicationResponse(result), nil
}

func (s *OriginationService) UpdateLoanApplication(ctx context.Context, req *fidesbffv1pb.FidesBffLoanApplicationServiceUpdateLoanApplicationRequest) (*fidesbffv1pb.FidesBffLoanApplicationServiceUpdateLoanApplicationResponse, error) {
	principal, ok := bffkit.PrincipalFromContext(ctx)
	if !ok {
		return nil, bffkit.UnauthorizedError()
	}
	headers := requestHeaders(ctx)
	raw, err := marshalUpdateLoanApplicationRequest(req)
	if err != nil {
		return nil, err
	}
	result, err := s.uc.PatchLoanApplication(ctx, biz.PatchLoanApplicationCommand{
		ApplicantID:    principal.ApplicantID,
		ApplicationID:  req.GetApplicationId(),
		IdempotencyKey: headers.Get(bffkit.HeaderIdempotencyKey),
		TraceParent:    headers.Get(bffkit.HeaderTraceParent),
		TraceState:     headers.Get(bffkit.HeaderTraceState),
		RawRequest:     raw,
	})
	if err != nil {
		return nil, originationHTTPError(err)
	}
	return &fidesbffv1pb.FidesBffLoanApplicationServiceUpdateLoanApplicationResponse{
		ApplicationId: result.ApplicationID,
		Status:        result.Status,
		CurrentStep:   result.CurrentStep,
	}, nil
}

func mapCreateLoanApplicationResponse(result biz.LoanApplicationSummary) *fidesbffv1pb.FidesBffLoanApplicationServiceCreateLoanApplicationResponse {
	return &fidesbffv1pb.FidesBffLoanApplicationServiceCreateLoanApplicationResponse{
		ApplicationId: result.ApplicationID,
		Status:        result.Status,
		CurrentStep:   result.CurrentStep,
	}
}

func mapGetLoanApplicationResponse(result biz.LoanApplicationDetail) *fidesbffv1pb.FidesBffLoanApplicationServiceGetLoanApplicationResponse {
	return &fidesbffv1pb.FidesBffLoanApplicationServiceGetLoanApplicationResponse{
		ApplicationId: result.ApplicationID,
		Loan: &fidesbffv1pb.FidesBffLoanTerms{
			Amount:  result.Loan.Amount,
			Term:    int32(result.Loan.Term),
			Purpose: result.Loan.Purpose,
		},
		AcceptedQuote: &fidesbffv1pb.FidesBffAcceptedQuote{
			QuoteId:       result.AcceptedQuote.QuoteID,
			Monthly:       result.AcceptedQuote.Monthly,
			Apr:           result.AcceptedQuote.APR,
			TotalInterest: result.AcceptedQuote.TotalInterest,
			TotalPayable:  result.AcceptedQuote.TotalPayable,
			ValidUntil:    result.AcceptedQuote.ValidUntil,
		},
		Status:      result.Status,
		CurrentStep: result.CurrentStep,
	}
}

func marshalCreateLoanApplicationRequest(req *fidesbffv1pb.FidesBffLoanApplicationServiceCreateLoanApplicationRequest) (json.RawMessage, error) {
	return marshalLoanApplicationBody(req.GetProductCode(), req.GetQuoteId(), req.GetLoan())
}

func marshalUpdateLoanApplicationRequest(req *fidesbffv1pb.FidesBffLoanApplicationServiceUpdateLoanApplicationRequest) (json.RawMessage, error) {
	return marshalLoanApplicationBody("", req.GetQuoteId(), req.GetLoan())
}

func marshalLoanApplicationBody(productCode string, quoteID string, loan *fidesbffv1pb.FidesBffLoanTerms) (json.RawMessage, error) {
	body := struct {
		ProductCode string         `json:"productCode,omitempty"`
		QuoteID     string         `json:"quoteId"`
		Loan        *loanTermsJSON `json:"loan,omitempty"`
	}{
		ProductCode: productCode,
		QuoteID:     quoteID,
		Loan:        loanTermsBody(loan),
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	return raw, nil
}

type loanTermsJSON struct {
	Amount  string `json:"amount"`
	Term    int32  `json:"term"`
	Purpose string `json:"purpose"`
}

func loanTermsBody(loan *fidesbffv1pb.FidesBffLoanTerms) *loanTermsJSON {
	if loan == nil {
		return nil
	}
	return &loanTermsJSON{Amount: loan.GetAmount(), Term: loan.GetTerm(), Purpose: loan.GetPurpose()}
}

func originationHTTPError(err error) error {
	var originationErr *biz.OriginationError
	if !errors.As(err, &originationErr) {
		return err
	}
	var status int
	switch originationErr.Code {
	case biz.OriginationCodeIdempotencyKeyRequired:
		status = nethttp.StatusBadRequest
	case biz.OriginationCodeForbidden:
		status = nethttp.StatusForbidden
	case biz.OriginationCodeNotFound:
		status = nethttp.StatusNotFound
	case biz.OriginationCodeQuoteExpired:
		status = nethttp.StatusGone
	case biz.OriginationCodeAmountOutOfRange, biz.OriginationCodeValidation:
		status = nethttp.StatusUnprocessableEntity
	case biz.OriginationCodeUnavailable:
		status = nethttp.StatusBadGateway
	default:
		return &bffkit.HTTPError{Status: nethttp.StatusBadGateway, Code: biz.OriginationCodeUnavailable, Message: "origination-api is unavailable"}
	}
	return &bffkit.HTTPError{Status: status, Code: originationErr.Code, Message: originationErr.Message}
}
