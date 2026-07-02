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

type PricingService struct {
	uc *biz.PricingUsecase
}

func NewPricingService(uc *biz.PricingUsecase) *PricingService {
	return &PricingService{uc: uc}
}

func (s *PricingService) CreateQuote(ctx context.Context, req *fidesbffv1pb.FidesBffPricingServiceCreateQuoteRequest) (*fidesbffv1pb.FidesBffPricingServiceCreateQuoteResponse, error) {
	principal, ok := bffkit.PrincipalFromContext(ctx)
	if !ok {
		return nil, bffkit.UnauthorizedError()
	}
	headers := requestHeaders(ctx)
	raw, err := marshalCreateQuoteRequest(req)
	if err != nil {
		return nil, err
	}
	result, err := s.uc.CreateQuote(ctx, biz.CreateQuoteCommand{
		ApplicantID: principal.ApplicantID,
		ProductCode: req.GetProductCode(),
		Amount:      json.RawMessage(quoteAmountRaw(req.GetAmount())),
		Term:        int(req.GetTerm()),
		Purpose:     req.GetPurpose(),
		TraceParent: headers.Get(bffkit.HeaderTraceParent),
		TraceState:  headers.Get(bffkit.HeaderTraceState),
		RawRequest:  raw,
	})
	if err != nil {
		return nil, pricingHTTPError(err)
	}
	return &fidesbffv1pb.FidesBffPricingServiceCreateQuoteResponse{
		QuoteId:       result.QuoteID,
		Monthly:       result.Monthly,
		Apr:           result.APR,
		TotalInterest: result.TotalInterest,
		TotalPayable:  result.TotalPayable,
		ValidUntil:    result.ValidUntil,
	}, nil
}

func marshalCreateQuoteRequest(req *fidesbffv1pb.FidesBffPricingServiceCreateQuoteRequest) (json.RawMessage, error) {
	raw, err := json.Marshal(struct {
		ProductCode string `json:"productCode"`
		Amount      string `json:"amount"`
		Term        int32  `json:"term"`
		Purpose     string `json:"purpose"`
	}{
		ProductCode: req.GetProductCode(),
		Amount:      req.GetAmount(),
		Term:        req.GetTerm(),
		Purpose:     req.GetPurpose(),
	})
	if err != nil {
		return nil, err
	}
	return raw, nil
}

func quoteAmountRaw(amount string) []byte {
	raw, err := json.Marshal(amount)
	if err != nil {
		return []byte(`""`)
	}
	return raw
}

func pricingHTTPError(err error) error {
	var pricingErr *biz.PricingError
	if !errors.As(err, &pricingErr) {
		return err
	}
	switch pricingErr.Code {
	case biz.PricingCodeAmountOutOfRange:
		return &bffkit.HTTPError{Status: nethttp.StatusUnprocessableEntity, Code: pricingErr.Code, Message: pricingErr.Message}
	case biz.PricingCodeValidation:
		return &bffkit.HTTPError{Status: nethttp.StatusUnprocessableEntity, Code: pricingErr.Code, Message: pricingErr.Message}
	case biz.PricingCodeQuoteUnavailable:
		return &bffkit.HTTPError{Status: nethttp.StatusBadGateway, Code: pricingErr.Code, Message: pricingErr.Message}
	default:
		return &bffkit.HTTPError{Status: nethttp.StatusBadGateway, Code: biz.PricingCodeQuoteUnavailable, Message: "quote-api is unavailable"}
	}
}
