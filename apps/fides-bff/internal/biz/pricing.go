package biz

import (
	"context"
	"encoding/json"
)

const (
	PricingCodeAmountOutOfRange = "amount_out_of_range"
	PricingCodeValidation       = "validation_error"
	PricingCodeQuoteUnavailable = "quote_unavailable"
)

type QuoteClient interface {
	CreateQuote(context.Context, CreateQuoteCommand) (QuoteResult, error)
}

type CreateQuoteCommand struct {
	ApplicantID string
	ProductCode string
	Amount      json.RawMessage
	Term        int
	Purpose     string
	TraceParent string
	TraceState  string
	RawRequest  json.RawMessage
}

type QuoteResult struct {
	QuoteID       string
	Monthly       string
	APR           string
	TotalInterest string
	TotalPayable  string
	ValidUntil    string
}

type PricingError struct {
	Code    string
	Message string
}

func (e *PricingError) Error() string {
	if e.Message != "" {
		return e.Message
	}
	return e.Code
}

type PricingUsecase struct {
	client QuoteClient
}

func NewPricingUsecase(client QuoteClient) *PricingUsecase {
	return &PricingUsecase{client: client}
}

func (uc *PricingUsecase) CreateQuote(ctx context.Context, command CreateQuoteCommand) (QuoteResult, error) {
	if uc == nil || uc.client == nil {
		return QuoteResult{}, &PricingError{Code: PricingCodeQuoteUnavailable, Message: "quote-api is unavailable"}
	}
	return uc.client.CreateQuote(ctx, command)
}
