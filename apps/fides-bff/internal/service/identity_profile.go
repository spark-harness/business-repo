package service

import (
	"encoding/json"
	"errors"
	"fmt"
	nethttp "net/http"

	khttp "github.com/go-kratos/kratos/v2/transport/http"
	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/biz"
)

type IdentityProfileService struct {
	uc *biz.IdentityProfileUsecase
}

func NewIdentityProfileService(uc *biz.IdentityProfileUsecase) *IdentityProfileService {
	return &IdentityProfileService{uc: uc}
}

func (s *IdentityProfileService) UpsertIdentityProfile(ctx khttp.Context) error {
	principal, ok := bffkit.PrincipalFromContext(ctx.Request().Context())
	if !ok {
		return bffkit.UnauthorizedError()
	}
	var req identityProfileUpsertRequest
	if err := json.NewDecoder(ctx.Request().Body).Decode(&req); err != nil {
		return bffkit.ValidationError([]bffkit.FieldError{{Field: "", Message: "invalid JSON request body"}})
	}
	result, err := s.uc.Upsert(ctx.Request().Context(), biz.UpsertIdentityProfileCommand{
		ApplicantID:   principal.ApplicantID,
		ApplicationID: req.ApplicationID,
		Profile: biz.IdentityProfile{
			HKIDBody:       req.Profile.HKIDBody,
			HKIDCheckDigit: req.Profile.HKIDCheckDigit,
			FirstName:      req.Profile.FirstName,
			LastName:       req.Profile.LastName,
			ChineseName:    req.Profile.ChineseName,
			Nationality:    req.Profile.Nationality.String(),
			DateOfBirth:    req.Profile.DateOfBirth,
		},
		TraceParent: ctx.Request().Header.Get(bffkit.HeaderTraceParent),
		TraceState:  ctx.Request().Header.Get(bffkit.HeaderTraceState),
	})
	if err != nil {
		return identityProfileHTTPError(err)
	}
	return ctx.JSON(nethttp.StatusOK, identityProfileUpsertResponse{
		Profile:     identityProfileResponse(result.Profile),
		CurrentStep: result.CurrentStep,
	})
}

func (s *IdentityProfileService) GetIdentityProfile(ctx khttp.Context) error {
	principal, ok := bffkit.PrincipalFromContext(ctx.Request().Context())
	if !ok {
		return bffkit.UnauthorizedError()
	}
	result, err := s.uc.Get(ctx.Request().Context(), biz.GetIdentityProfileCommand{
		ApplicantID:   principal.ApplicantID,
		ApplicationID: ctx.Request().URL.Query().Get("applicationId"),
		TraceParent:   ctx.Request().Header.Get(bffkit.HeaderTraceParent),
		TraceState:    ctx.Request().Header.Get(bffkit.HeaderTraceState),
	})
	if err != nil {
		return identityProfileHTTPError(err)
	}
	response := identityProfileGetResponse{Empty: result.Empty}
	if !result.Empty {
		profile := identityProfileResponse(result.Profile)
		response.Profile = &profile
	}
	return ctx.JSON(nethttp.StatusOK, response)
}

type identityProfileUpsertRequest struct {
	ApplicationID string                 `json:"applicationId"`
	Profile       identityProfileRequest `json:"profile"`
}

type identityProfileRequest struct {
	HKIDBody       string                     `json:"hkidBody"`
	HKIDCheckDigit string                     `json:"hkidCheckDigit"`
	FirstName      string                     `json:"firstName"`
	LastName       string                     `json:"lastName"`
	ChineseName    string                     `json:"chineseName"`
	Nationality    identityProfileNationality `json:"nationality"`
	DateOfBirth    string                     `json:"dateOfBirth"`
}

type identityProfileNationality string

func (n *identityProfileNationality) UnmarshalJSON(data []byte) error {
	var text string
	if err := json.Unmarshal(data, &text); err == nil {
		*n = identityProfileNationality(text)
		return nil
	}
	var number int
	if err := json.Unmarshal(data, &number); err != nil {
		return err
	}
	value, ok := identityProfileNationalityByNumber[number]
	if !ok {
		return fmt.Errorf("unsupported nationality enum: %d", number)
	}
	*n = identityProfileNationality(value)
	return nil
}

func (n identityProfileNationality) String() string {
	return string(n)
}

var identityProfileNationalityByNumber = map[int]string{
	1:  "chinese",
	2:  "hong_kong",
	3:  "british",
	4:  "indian",
	5:  "filipino",
	6:  "indonesian",
	7:  "pakistani",
	8:  "american",
	9:  "australian",
	10: "canadian",
	11: "other",
}

type identityProfileResponse struct {
	HKIDBody       string `json:"hkidBody"`
	HKIDCheckDigit string `json:"hkidCheckDigit"`
	FirstName      string `json:"firstName"`
	LastName       string `json:"lastName"`
	ChineseName    string `json:"chineseName"`
	Nationality    string `json:"nationality"`
	DateOfBirth    string `json:"dateOfBirth"`
}

type identityProfileUpsertResponse struct {
	Profile     identityProfileResponse `json:"profile"`
	CurrentStep string                  `json:"currentStep"`
}

type identityProfileGetResponse struct {
	Empty   bool                     `json:"empty"`
	Profile *identityProfileResponse `json:"profile,omitempty"`
}

func identityProfileHTTPError(err error) error {
	var profileErr *biz.IdentityProfileError
	if !errors.As(err, &profileErr) {
		return err
	}
	status := nethttp.StatusBadGateway
	switch profileErr.Code {
	case biz.IdentityProfileCodeApplicationRequired:
		status = nethttp.StatusBadRequest
	case biz.IdentityProfileCodeForbidden:
		status = nethttp.StatusForbidden
	case biz.IdentityProfileCodeValidation, biz.IdentityProfileCodeHkidInvalid, biz.IdentityProfileCodeAgeOutOfRange:
		status = nethttp.StatusUnprocessableEntity
	case biz.IdentityProfileCodeApplicantUnavailable, biz.IdentityProfileCodeOriginationUnavailable:
		status = nethttp.StatusBadGateway
	}
	return &bffkit.HTTPError{Status: status, Code: profileErr.Code, Message: profileErr.Message}
}
