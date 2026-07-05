package biz

import (
	"context"
	"encoding/json"
)

const (
	OriginationCodeIdempotencyKeyRequired = "idempotency_key_required"
	OriginationCodeForbidden              = "forbidden"
	OriginationCodeNotFound               = "not_found"
	OriginationCodeQuoteExpired           = "quote_expired"
	OriginationCodeAmountOutOfRange       = "amount_out_of_range"
	OriginationCodeValidation             = "validation_error"
	OriginationCodeUnavailable            = "origination_unavailable"
)

type OriginationClient interface {
	CreateLoanApplication(context.Context, CreateLoanApplicationCommand) (LoanApplicationSummary, error)
	GetLoanApplication(context.Context, GetLoanApplicationCommand) (LoanApplicationDetail, error)
	PatchLoanApplication(context.Context, PatchLoanApplicationCommand) (LoanApplicationSummary, error)
}

type CreateLoanApplicationCommand struct {
	ApplicantID    string
	IdempotencyKey string
	RawRequest     json.RawMessage
}

type GetLoanApplicationCommand struct {
	ApplicantID   string
	ApplicationID string
}

type PatchLoanApplicationCommand struct {
	ApplicantID    string
	ApplicationID  string
	IdempotencyKey string
	RawRequest     json.RawMessage
}

type LoanApplicationSummary struct {
	ApplicationID string
	Status        string
	CurrentStep   string
}

type LoanApplicationDetail struct {
	ApplicationID string
	Loan          LoanTerms
	AcceptedQuote AcceptedQuote
	Status        string
	CurrentStep   string
}

type LoanTerms struct {
	Amount  string
	Term    int
	Purpose string
}

type AcceptedQuote struct {
	QuoteID       string
	Monthly       string
	APR           string
	TotalInterest string
	TotalPayable  string
	ValidUntil    string
}

type OriginationError struct {
	Code    string
	Message string
}

func (e *OriginationError) Error() string {
	if e.Message != "" {
		return e.Message
	}
	return e.Code
}

type OriginationUsecase struct {
	client OriginationClient
}

func NewOriginationUsecase(client OriginationClient) *OriginationUsecase {
	return &OriginationUsecase{client: client}
}

func (uc *OriginationUsecase) CreateLoanApplication(ctx context.Context, command CreateLoanApplicationCommand) (LoanApplicationSummary, error) {
	if uc == nil || uc.client == nil {
		return LoanApplicationSummary{}, &OriginationError{Code: OriginationCodeUnavailable, Message: "origination-api is unavailable"}
	}
	return uc.client.CreateLoanApplication(ctx, command)
}

func (uc *OriginationUsecase) GetLoanApplication(ctx context.Context, command GetLoanApplicationCommand) (LoanApplicationDetail, error) {
	if uc == nil || uc.client == nil {
		return LoanApplicationDetail{}, &OriginationError{Code: OriginationCodeUnavailable, Message: "origination-api is unavailable"}
	}
	return uc.client.GetLoanApplication(ctx, command)
}

func (uc *OriginationUsecase) PatchLoanApplication(ctx context.Context, command PatchLoanApplicationCommand) (LoanApplicationSummary, error) {
	if uc == nil || uc.client == nil {
		return LoanApplicationSummary{}, &OriginationError{Code: OriginationCodeUnavailable, Message: "origination-api is unavailable"}
	}
	return uc.client.PatchLoanApplication(ctx, command)
}
