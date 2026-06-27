package service

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	nethttp "net/http"

	khttp "github.com/go-kratos/kratos/v2/transport/http"
	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/biz"
)

type PricingService struct {
	uc *biz.PricingUsecase
}

func NewPricingService(uc *biz.PricingUsecase) *PricingService {
	return &PricingService{uc: uc}
}

func (s *PricingService) CreateQuote(ctx khttp.Context) error {
	principal, ok := bffkit.PrincipalFromContext(ctx.Request().Context())
	if !ok {
		return bffkit.UnauthorizedError()
	}
	body, err := decodeCreateQuoteRequest(ctx.Request())
	if err != nil {
		return err
	}
	result, err := s.uc.CreateQuote(ctx.Request().Context(), biz.CreateQuoteCommand{
		ApplicantID: principal.ApplicantID,
		ProductCode: body.ProductCode,
		Amount:      body.Amount,
		Term:        body.Term,
		Purpose:     body.Purpose,
		TraceParent: ctx.Request().Header.Get(bffkit.HeaderTraceParent),
		TraceState:  ctx.Request().Header.Get(bffkit.HeaderTraceState),
		RawRequest:  body.Raw,
	})
	if err != nil {
		return pricingHTTPError(err)
	}
	return ctx.JSON(nethttp.StatusOK, createQuoteResponse{
		QuoteID:       result.QuoteID,
		Monthly:       result.Monthly,
		APR:           result.APR,
		TotalInterest: result.TotalInterest,
		TotalPayable:  result.TotalPayable,
		ValidUntil:    result.ValidUntil,
	})
}

type createQuoteRequest struct {
	ProductCode string          `json:"productCode"`
	Amount      json.RawMessage `json:"amount"`
	Term        int             `json:"term"`
	Purpose     string          `json:"purpose"`
	Raw         json.RawMessage
}

type createQuoteResponse struct {
	QuoteID       string `json:"quoteId"`
	Monthly       string `json:"monthly"`
	APR           string `json:"apr"`
	TotalInterest string `json:"totalInterest"`
	TotalPayable  string `json:"totalPayable"`
	ValidUntil    string `json:"validUntil"`
}

func decodeCreateQuoteRequest(r *nethttp.Request) (createQuoteRequest, error) {
	data, err := io.ReadAll(r.Body)
	if err != nil {
		return createQuoteRequest{}, bffkit.ValidationError([]bffkit.FieldError{{Field: "", Message: "invalid JSON request body"}})
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	var parsed any
	if err := decoder.Decode(&parsed); err != nil {
		return createQuoteRequest{}, bffkit.ValidationError([]bffkit.FieldError{{Field: "", Message: "invalid JSON request body"}})
	}
	var body createQuoteRequest
	if err := json.Unmarshal(data, &body); err != nil {
		return createQuoteRequest{}, bffkit.ValidationError([]bffkit.FieldError{{Field: "", Message: "invalid JSON request body"}})
	}
	body.Raw = append(json.RawMessage(nil), data...)
	return body, nil
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
