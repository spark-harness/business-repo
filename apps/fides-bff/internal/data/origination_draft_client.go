package data

import (
	"context"
	"time"

	originationv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/origination/v1"
	"github.com/spark/bffkit"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/conf"
)

type OriginationDraftClient struct {
	resolver    ServiceResolver
	timeout     time.Duration
	dialOptions []grpc.DialOption
}

func NewOriginationDraftClient(c *conf.Origination) *OriginationDraftClient {
	timeout := 3 * time.Second
	if c != nil {
		if parsed, err := time.ParseDuration(c.HTTP.Timeout); err == nil && parsed > 0 {
			timeout = parsed
		}
	}
	return &OriginationDraftClient{
		resolver:    NewOriginationGRPCConsulResolver(c),
		timeout:     timeout,
		dialOptions: []grpc.DialOption{grpc.WithTransportCredentials(insecure.NewCredentials())},
	}
}

func (c *OriginationDraftClient) AdvanceApplicationStep(ctx context.Context, command biz.AdvanceApplicationStepCommand) (biz.AdvanceApplicationStepResult, error) {
	if c == nil || c.resolver == nil {
		return biz.AdvanceApplicationStepResult{}, &biz.IdentityProfileError{Code: biz.IdentityProfileCodeOriginationUnavailable, Message: "origination-api is unavailable"}
	}
	target, err := c.resolver.Resolve(ctx)
	if err != nil {
		return biz.AdvanceApplicationStepResult{}, &biz.IdentityProfileError{Code: biz.IdentityProfileCodeOriginationUnavailable, Message: "origination-api is unavailable"}
	}
	conn, err := grpcNewClient(target, c.dialOptions...)
	if err != nil {
		return biz.AdvanceApplicationStepResult{}, &biz.IdentityProfileError{Code: biz.IdentityProfileCodeOriginationUnavailable, Message: "origination-api is unavailable"}
	}
	defer func() { _ = conn.Close() }()
	rpcCtx := bffkit.OutgoingGRPCContext(ctx)
	if c.timeout > 0 {
		var cancel context.CancelFunc
		rpcCtx, cancel = context.WithTimeout(rpcCtx, c.timeout)
		defer cancel()
	}
	resp, err := originationv1pb.NewOriginationDraftServiceClient(conn).AdvanceApplicationStep(rpcCtx, &originationv1pb.AdvanceApplicationStepRequest{
		ApplicantId:   command.ApplicantID,
		ApplicationId: command.ApplicationID,
		TargetStep:    originationv1pb.ApplicationStep_APPLICATION_STEP_IDENTITY_INFORMATION,
	})
	if err != nil {
		return biz.AdvanceApplicationStepResult{}, draftErrorFromGRPC(err)
	}
	return biz.AdvanceApplicationStepResult{
		ApplicationID: resp.GetApplicationId(),
		CurrentStep:   originationStepValue(resp.GetCurrentStep()),
	}, nil
}

func draftErrorFromGRPC(err error) error {
	st, ok := status.FromError(err)
	if !ok {
		return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeOriginationUnavailable, Message: "origination-api is unavailable"}
	}
	switch st.Code() {
	case codes.Unavailable, codes.DeadlineExceeded:
		return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeOriginationUnavailable, Message: "origination-api is unavailable"}
	case codes.PermissionDenied:
		return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeForbidden, Message: "forbidden"}
	case codes.InvalidArgument:
		if st.Message() == biz.IdentityProfileCodeApplicationRequired {
			return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeApplicationRequired, Message: "applicationId is required"}
		}
	}
	return &biz.IdentityProfileError{Code: biz.IdentityProfileCodeOriginationUnavailable, Message: "origination-api is unavailable"}
}

func originationStepValue(value originationv1pb.ApplicationStep) string {
	switch value {
	case originationv1pb.ApplicationStep_APPLICATION_STEP_IDENTITY_INFORMATION:
		return "identity_information"
	case originationv1pb.ApplicationStep_APPLICATION_STEP_LOAN_REQUEST:
		return "loan_request"
	default:
		return ""
	}
}
