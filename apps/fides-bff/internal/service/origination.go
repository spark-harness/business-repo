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

type OriginationService struct {
	uc *biz.OriginationUsecase
}

func NewOriginationService(uc *biz.OriginationUsecase) *OriginationService {
	return &OriginationService{uc: uc}
}

func (s *OriginationService) CreateLoanApplication(ctx khttp.Context) error {
	principal, ok := bffkit.PrincipalFromContext(ctx.Request().Context())
	if !ok {
		return bffkit.UnauthorizedError()
	}
	body, err := decodeJSONRequest(ctx.Request())
	if err != nil {
		return err
	}
	result, err := s.uc.CreateLoanApplication(ctx.Request().Context(), biz.CreateLoanApplicationCommand{
		ApplicantID:    principal.ApplicantID,
		IdempotencyKey: ctx.Request().Header.Get(bffkit.HeaderIdempotencyKey),
		TraceParent:    ctx.Request().Header.Get(bffkit.HeaderTraceParent),
		TraceState:     ctx.Request().Header.Get(bffkit.HeaderTraceState),
		RawRequest:     body,
	})
	if err != nil {
		return originationHTTPError(err)
	}
	return ctx.JSON(nethttp.StatusOK, loanApplicationSummaryResponse(result))
}

func (s *OriginationService) GetLoanApplication(ctx khttp.Context) error {
	principal, ok := bffkit.PrincipalFromContext(ctx.Request().Context())
	if !ok {
		return bffkit.UnauthorizedError()
	}
	result, err := s.uc.GetLoanApplication(ctx.Request().Context(), biz.GetLoanApplicationCommand{
		ApplicantID:   principal.ApplicantID,
		ApplicationID: ctx.Vars().Get("applicationId"),
		TraceParent:   ctx.Request().Header.Get(bffkit.HeaderTraceParent),
		TraceState:    ctx.Request().Header.Get(bffkit.HeaderTraceState),
	})
	if err != nil {
		return originationHTTPError(err)
	}
	return ctx.JSON(nethttp.StatusOK, mapLoanApplicationDetailResponse(result))
}

func (s *OriginationService) PatchLoanApplication(ctx khttp.Context) error {
	principal, ok := bffkit.PrincipalFromContext(ctx.Request().Context())
	if !ok {
		return bffkit.UnauthorizedError()
	}
	body, err := decodeJSONRequest(ctx.Request())
	if err != nil {
		return err
	}
	result, err := s.uc.PatchLoanApplication(ctx.Request().Context(), biz.PatchLoanApplicationCommand{
		ApplicantID:    principal.ApplicantID,
		ApplicationID:  ctx.Vars().Get("applicationId"),
		IdempotencyKey: ctx.Request().Header.Get(bffkit.HeaderIdempotencyKey),
		TraceParent:    ctx.Request().Header.Get(bffkit.HeaderTraceParent),
		TraceState:     ctx.Request().Header.Get(bffkit.HeaderTraceState),
		RawRequest:     body,
	})
	if err != nil {
		return originationHTTPError(err)
	}
	return ctx.JSON(nethttp.StatusOK, loanApplicationSummaryResponse(result))
}

type loanApplicationSummaryResponse struct {
	ApplicationID string `json:"applicationId"`
	Status        string `json:"status"`
	CurrentStep   string `json:"currentStep"`
}

type loanApplicationDetailResponse struct {
	ApplicationID string                `json:"applicationId"`
	Loan          loanTermsResponse     `json:"loan"`
	AcceptedQuote acceptedQuoteResponse `json:"acceptedQuote"`
	Status        string                `json:"status"`
	CurrentStep   string                `json:"currentStep"`
}

type loanTermsResponse struct {
	Amount  string `json:"amount"`
	Term    int    `json:"term"`
	Purpose string `json:"purpose"`
}

type acceptedQuoteResponse struct {
	QuoteID       string `json:"quoteId"`
	Monthly       string `json:"monthly"`
	APR           string `json:"apr"`
	TotalInterest string `json:"totalInterest"`
	TotalPayable  string `json:"totalPayable"`
	ValidUntil    string `json:"validUntil"`
}

func mapLoanApplicationDetailResponse(result biz.LoanApplicationDetail) loanApplicationDetailResponse {
	return loanApplicationDetailResponse{
		ApplicationID: result.ApplicationID,
		Loan: loanTermsResponse{
			Amount:  result.Loan.Amount,
			Term:    result.Loan.Term,
			Purpose: result.Loan.Purpose,
		},
		AcceptedQuote: acceptedQuoteResponse{
			QuoteID:       result.AcceptedQuote.QuoteID,
			Monthly:       result.AcceptedQuote.Monthly,
			APR:           result.AcceptedQuote.APR,
			TotalInterest: result.AcceptedQuote.TotalInterest,
			TotalPayable:  result.AcceptedQuote.TotalPayable,
			ValidUntil:    result.AcceptedQuote.ValidUntil,
		},
		Status:      result.Status,
		CurrentStep: result.CurrentStep,
	}
}

func decodeJSONRequest(r *nethttp.Request) (json.RawMessage, error) {
	data, err := io.ReadAll(r.Body)
	if err != nil {
		return nil, bffkit.ValidationError([]bffkit.FieldError{{Field: "", Message: "invalid JSON request body"}})
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	var parsed any
	if err := decoder.Decode(&parsed); err != nil {
		return nil, bffkit.ValidationError([]bffkit.FieldError{{Field: "", Message: "invalid JSON request body"}})
	}
	return append(json.RawMessage(nil), data...), nil
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
