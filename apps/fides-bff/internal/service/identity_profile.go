package service

import (
	"context"
	"errors"
	nethttp "net/http"

	applicantv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/applicant/v1"
	fidesbffv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/fides-bff/v1"
	originationv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/origination/v1"
	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/biz"
)

type IdentityProfileService struct {
	uc *biz.IdentityProfileUsecase
}

func NewIdentityProfileService(uc *biz.IdentityProfileUsecase) *IdentityProfileService {
	return &IdentityProfileService{uc: uc}
}

func (s *IdentityProfileService) UpsertIdentityProfile(ctx context.Context, req *fidesbffv1pb.FidesBffIdentityProfileServiceUpsertIdentityProfileRequest) (*fidesbffv1pb.FidesBffIdentityProfileServiceUpsertIdentityProfileResponse, error) {
	principal, ok := bffkit.PrincipalFromContext(ctx)
	if !ok {
		return nil, bffkit.UnauthorizedError()
	}
	headers := requestHeaders(ctx)
	profile := req.GetProfile()
	result, err := s.uc.Upsert(ctx, biz.UpsertIdentityProfileCommand{
		ApplicantID:   principal.ApplicantID,
		ApplicationID: req.GetApplicationId(),
		Profile: biz.IdentityProfile{
			HKIDBody:       profile.GetHkidBody(),
			HKIDCheckDigit: profile.GetHkidCheckDigit(),
			FirstName:      profile.GetFirstName(),
			LastName:       profile.GetLastName(),
			ChineseName:    profile.GetChineseName(),
			Nationality:    nationalityFromProto(profile.GetNationality()),
			DateOfBirth:    profile.GetDateOfBirth(),
		},
		TraceParent: headers.Get(bffkit.HeaderTraceParent),
		TraceState:  headers.Get(bffkit.HeaderTraceState),
	})
	if err != nil {
		return nil, identityProfileHTTPError(err)
	}
	return &fidesbffv1pb.FidesBffIdentityProfileServiceUpsertIdentityProfileResponse{
		Profile:     identityProfileToProto(result.Profile),
		CurrentStep: applicationStepToProto(result.CurrentStep),
	}, nil
}

func (s *IdentityProfileService) GetIdentityProfile(ctx context.Context, req *fidesbffv1pb.FidesBffIdentityProfileServiceGetIdentityProfileRequest) (*fidesbffv1pb.FidesBffIdentityProfileServiceGetIdentityProfileResponse, error) {
	principal, ok := bffkit.PrincipalFromContext(ctx)
	if !ok {
		return nil, bffkit.UnauthorizedError()
	}
	headers := requestHeaders(ctx)
	result, err := s.uc.Get(ctx, biz.GetIdentityProfileCommand{
		ApplicantID:   principal.ApplicantID,
		ApplicationID: req.GetApplicationId(),
		TraceParent:   headers.Get(bffkit.HeaderTraceParent),
		TraceState:    headers.Get(bffkit.HeaderTraceState),
	})
	if err != nil {
		return nil, identityProfileHTTPError(err)
	}
	response := &fidesbffv1pb.FidesBffIdentityProfileServiceGetIdentityProfileResponse{Empty: result.Empty}
	if !result.Empty {
		response.Profile = identityProfileToProto(result.Profile)
	}
	return response, nil
}

func identityProfileToProto(profile biz.IdentityProfile) *fidesbffv1pb.FidesBffIdentityProfile {
	return &fidesbffv1pb.FidesBffIdentityProfile{
		HkidBody:       profile.HKIDBody,
		HkidCheckDigit: profile.HKIDCheckDigit,
		FirstName:      profile.FirstName,
		LastName:       profile.LastName,
		ChineseName:    profile.ChineseName,
		Nationality:    nationalityToProto(profile.Nationality),
		DateOfBirth:    profile.DateOfBirth,
	}
}

func nationalityFromProto(n applicantv1pb.Nationality) string {
	switch n {
	case applicantv1pb.Nationality_NATIONALITY_CHINESE:
		return "chinese"
	case applicantv1pb.Nationality_NATIONALITY_HONG_KONG:
		return "hong_kong"
	case applicantv1pb.Nationality_NATIONALITY_BRITISH:
		return "british"
	case applicantv1pb.Nationality_NATIONALITY_INDIAN:
		return "indian"
	case applicantv1pb.Nationality_NATIONALITY_FILIPINO:
		return "filipino"
	case applicantv1pb.Nationality_NATIONALITY_INDONESIAN:
		return "indonesian"
	case applicantv1pb.Nationality_NATIONALITY_PAKISTANI:
		return "pakistani"
	case applicantv1pb.Nationality_NATIONALITY_AMERICAN:
		return "american"
	case applicantv1pb.Nationality_NATIONALITY_AUSTRALIAN:
		return "australian"
	case applicantv1pb.Nationality_NATIONALITY_CANADIAN:
		return "canadian"
	case applicantv1pb.Nationality_NATIONALITY_OTHER:
		return "other"
	default:
		return ""
	}
}

func nationalityToProto(n string) applicantv1pb.Nationality {
	switch n {
	case "chinese":
		return applicantv1pb.Nationality_NATIONALITY_CHINESE
	case "hong_kong":
		return applicantv1pb.Nationality_NATIONALITY_HONG_KONG
	case "british":
		return applicantv1pb.Nationality_NATIONALITY_BRITISH
	case "indian":
		return applicantv1pb.Nationality_NATIONALITY_INDIAN
	case "filipino":
		return applicantv1pb.Nationality_NATIONALITY_FILIPINO
	case "indonesian":
		return applicantv1pb.Nationality_NATIONALITY_INDONESIAN
	case "pakistani":
		return applicantv1pb.Nationality_NATIONALITY_PAKISTANI
	case "american":
		return applicantv1pb.Nationality_NATIONALITY_AMERICAN
	case "australian":
		return applicantv1pb.Nationality_NATIONALITY_AUSTRALIAN
	case "canadian":
		return applicantv1pb.Nationality_NATIONALITY_CANADIAN
	case "other":
		return applicantv1pb.Nationality_NATIONALITY_OTHER
	default:
		return applicantv1pb.Nationality_NATIONALITY_UNSPECIFIED
	}
}

func applicationStepToProto(step string) originationv1pb.ApplicationStep {
	switch step {
	case "loan_request":
		return originationv1pb.ApplicationStep_APPLICATION_STEP_LOAN_REQUEST
	case "identity_information":
		return originationv1pb.ApplicationStep_APPLICATION_STEP_IDENTITY_INFORMATION
	default:
		return originationv1pb.ApplicationStep_APPLICATION_STEP_UNSPECIFIED
	}
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
