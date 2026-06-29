package biz

import "context"

const (
	IdentityProfileCodeApplicationRequired    = "application_required"
	IdentityProfileCodeForbidden              = "forbidden"
	IdentityProfileCodeValidation             = "validation_error"
	IdentityProfileCodeHkidInvalid            = "hkid_invalid"
	IdentityProfileCodeAgeOutOfRange          = "age_out_of_range"
	IdentityProfileCodeApplicantUnavailable   = "applicant_unavailable"
	IdentityProfileCodeOriginationUnavailable = "origination_unavailable"
)

type ApplicantProfileClient interface {
	UpsertIdentityProfile(context.Context, UpsertIdentityProfileCommand) (IdentityProfile, error)
	GetIdentityProfile(context.Context, GetIdentityProfileCommand) (GetIdentityProfileResult, error)
}

type OriginationDraftClient interface {
	AdvanceApplicationStep(context.Context, AdvanceApplicationStepCommand) (AdvanceApplicationStepResult, error)
}

type IdentityProfile struct {
	HKIDBody       string
	HKIDCheckDigit string
	FirstName      string
	LastName       string
	ChineseName    string
	Nationality    string
	DateOfBirth    string
}

type UpsertIdentityProfileCommand struct {
	ApplicantID   string
	ApplicationID string
	Profile       IdentityProfile
	TraceParent   string
	TraceState    string
}

type GetIdentityProfileCommand struct {
	ApplicantID   string
	ApplicationID string
	TraceParent   string
	TraceState    string
}

type GetIdentityProfileResult struct {
	Empty   bool
	Profile IdentityProfile
}

type AdvanceApplicationStepCommand struct {
	ApplicantID   string
	ApplicationID string
	TraceParent   string
	TraceState    string
}

type AdvanceApplicationStepResult struct {
	ApplicationID string
	CurrentStep   string
}

type UpsertIdentityProfileResult struct {
	Profile     IdentityProfile
	CurrentStep string
}

type IdentityProfileError struct {
	Code    string
	Message string
}

func (e *IdentityProfileError) Error() string {
	if e.Message != "" {
		return e.Message
	}
	return e.Code
}

type IdentityProfileUsecase struct {
	applicant   ApplicantProfileClient
	origination OriginationDraftClient
}

func NewIdentityProfileUsecase(applicant ApplicantProfileClient, origination OriginationDraftClient) *IdentityProfileUsecase {
	return &IdentityProfileUsecase{applicant: applicant, origination: origination}
}

func (uc *IdentityProfileUsecase) Upsert(ctx context.Context, command UpsertIdentityProfileCommand) (UpsertIdentityProfileResult, error) {
	if command.ApplicationID == "" {
		return UpsertIdentityProfileResult{}, &IdentityProfileError{Code: IdentityProfileCodeApplicationRequired, Message: "applicationId is required"}
	}
	if uc == nil || uc.applicant == nil {
		return UpsertIdentityProfileResult{}, &IdentityProfileError{Code: IdentityProfileCodeApplicantUnavailable, Message: "applicant-api is unavailable"}
	}
	if uc.origination == nil {
		return UpsertIdentityProfileResult{}, &IdentityProfileError{Code: IdentityProfileCodeOriginationUnavailable, Message: "origination-api is unavailable"}
	}
	profile, err := uc.applicant.UpsertIdentityProfile(ctx, command)
	if err != nil {
		return UpsertIdentityProfileResult{}, err
	}
	step, err := uc.origination.AdvanceApplicationStep(ctx, AdvanceApplicationStepCommand{
		ApplicantID:   command.ApplicantID,
		ApplicationID: command.ApplicationID,
		TraceParent:   command.TraceParent,
		TraceState:    command.TraceState,
	})
	if err != nil {
		return UpsertIdentityProfileResult{}, err
	}
	return UpsertIdentityProfileResult{Profile: profile, CurrentStep: step.CurrentStep}, nil
}

func (uc *IdentityProfileUsecase) Get(ctx context.Context, command GetIdentityProfileCommand) (GetIdentityProfileResult, error) {
	if command.ApplicationID == "" {
		return GetIdentityProfileResult{}, &IdentityProfileError{Code: IdentityProfileCodeApplicationRequired, Message: "applicationId is required"}
	}
	if uc == nil || uc.applicant == nil {
		return GetIdentityProfileResult{}, &IdentityProfileError{Code: IdentityProfileCodeApplicantUnavailable, Message: "applicant-api is unavailable"}
	}
	return uc.applicant.GetIdentityProfile(ctx, command)
}
