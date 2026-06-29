package data

import (
	"context"

	applicantv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/applicant/v1"
	"github.com/spark/fides-bff/internal/biz"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func (c *ApplicantAuthClient) UpsertIdentityProfile(ctx context.Context, command biz.UpsertIdentityProfileCommand) (biz.IdentityProfile, error) {
	client, cleanup, err := c.profileClient(ctx)
	if err != nil {
		return biz.IdentityProfile{}, &biz.IdentityProfileError{Code: biz.IdentityProfileCodeApplicantUnavailable, Message: "applicant-api is unavailable"}
	}
	defer cleanup()

	rpcCtx, cancel, endSpan := c.rpcContext(ctx, "UpsertIdentityProfile")
	defer cancel()
	resp, err := client.UpsertIdentityProfile(rpcCtx, &applicantv1pb.UpsertIdentityProfileRequest{
		ApplicantId:    command.ApplicantID,
		HkidBody:       command.Profile.HKIDBody,
		HkidCheckDigit: command.Profile.HKIDCheckDigit,
		FirstName:      command.Profile.FirstName,
		LastName:       command.Profile.LastName,
		ChineseName:    command.Profile.ChineseName,
		Nationality:    toApplicantNationality(command.Profile.Nationality),
		DateOfBirth:    command.Profile.DateOfBirth,
	})
	endSpan(err)
	if err != nil {
		return biz.IdentityProfile{}, profileErrorFromGRPC(err)
	}
	return identityProfileFromApplicant(resp.GetProfile()), nil
}

func (c *ApplicantAuthClient) GetIdentityProfile(ctx context.Context, command biz.GetIdentityProfileCommand) (biz.GetIdentityProfileResult, error) {
	client, cleanup, err := c.profileClient(ctx)
	if err != nil {
		return biz.GetIdentityProfileResult{}, &biz.IdentityProfileError{Code: biz.IdentityProfileCodeApplicantUnavailable, Message: "applicant-api is unavailable"}
	}
	defer cleanup()

	rpcCtx, cancel, endSpan := c.rpcContext(ctx, "GetIdentityProfile")
	defer cancel()
	resp, err := client.GetIdentityProfile(rpcCtx, &applicantv1pb.GetIdentityProfileRequest{
		ApplicantId: command.ApplicantID,
	})
	endSpan(err)
	if err != nil {
		return biz.GetIdentityProfileResult{}, profileErrorFromGRPC(err)
	}
	result := biz.GetIdentityProfileResult{Empty: resp.GetEmpty()}
	if !resp.GetEmpty() {
		result.Profile = identityProfileFromApplicant(resp.GetProfile())
	}
	return result, nil
}

func (c *ApplicantAuthClient) profileClient(ctx context.Context) (applicantv1pb.ApplicantProfileServiceClient, func(), error) {
	if c == nil || c.resolver == nil {
		return nil, func() {}, unavailable()
	}
	target, err := c.resolver.Resolve(ctx)
	if err != nil {
		return nil, func() {}, unavailable()
	}
	conn, err := grpcNewClient(target, c.dialOptions...)
	if err != nil {
		return nil, func() {}, unavailable()
	}
	return applicantv1pb.NewApplicantProfileServiceClient(conn), func() { _ = conn.Close() }, nil
}

func toApplicantNationality(value string) applicantv1pb.Nationality {
	switch value {
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

func identityProfileFromApplicant(profile *applicantv1pb.IdentityProfile) biz.IdentityProfile {
	if profile == nil {
		return biz.IdentityProfile{}
	}
	return biz.IdentityProfile{
		HKIDBody:       profile.GetHkidBody(),
		HKIDCheckDigit: profile.GetHkidCheckDigit(),
		FirstName:      profile.GetFirstName(),
		LastName:       profile.GetLastName(),
		ChineseName:    profile.GetChineseName(),
		Nationality:    applicantNationalityValue(profile.GetNationality()),
		DateOfBirth:    profile.GetDateOfBirth(),
	}
}

func applicantNationalityValue(value applicantv1pb.Nationality) string {
	switch value {
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

func profileErrorFromGRPC(err error) error {
	st, ok := status.FromError(err)
	if !ok {
		return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeApplicantUnavailable, Message: "applicant-api is unavailable"}
	}
	switch st.Code() {
	case codes.Unavailable, codes.DeadlineExceeded:
		return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeApplicantUnavailable, Message: "applicant-api is unavailable"}
	}
	switch st.Message() {
	case biz.IdentityProfileCodeHkidInvalid:
		return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeHkidInvalid, Message: "HKID is invalid"}
	case biz.IdentityProfileCodeAgeOutOfRange:
		return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeAgeOutOfRange, Message: "age is out of range"}
	case biz.IdentityProfileCodeValidation:
		return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeValidation, Message: "identity profile is invalid"}
	default:
		return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeApplicantUnavailable, Message: "applicant-api is unavailable"}
	}
}
