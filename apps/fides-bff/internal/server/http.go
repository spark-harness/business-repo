package server

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	nethttp "net/http"
	"strings"

	khttp "github.com/go-kratos/kratos/v3/transport/http"
	fidesbffv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/fides-bff/v1"
	"github.com/spark/bffkit"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"

	"github.com/spark/fides-bff/internal/conf"
	"github.com/spark/fides-bff/internal/service"
)

// NewHTTPServer builds the REST transport and registers the /api/v1 routes.
func NewHTTPServer(
	c *conf.Server,
	health *service.HealthService,
	auth *service.AuthService,
	pricing *service.PricingService,
	origination *service.OriginationService,
	identityProfile *service.IdentityProfileService,
	tokenValidator bffkit.TokenValidator,
	store bffkit.IdempotencyStore,
	logger *slog.Logger,
) *khttp.Server {
	opts := []khttp.ServerOption{
		khttp.RequestDecoder(compatRequestDecoder),
		khttp.ErrorEncoder(bffkit.ErrorEncoder),
		khttp.ResponseEncoder(compatResponseEncoder),
		khttp.Filter(
			bffkit.TraceFilter(logger),
			bffkit.CORSFilter(bffkit.CORSConfig{
				AllowedOrigins: c.CORS.AllowedOrigins,
				AllowedMethods: []string{
					nethttp.MethodGet,
					nethttp.MethodPost,
					nethttp.MethodPatch,
					nethttp.MethodPut,
					nethttp.MethodOptions,
				},
				MaxAgeSec: 600,
			}),
			protectedPathAuthFilter(tokenValidator),
			bffkit.IdempotencyFilter(store),
		),
	}
	if c.HTTP.Network != "" {
		opts = append(opts, khttp.Network(c.HTTP.Network))
	}
	if c.HTTP.Addr != "" {
		opts = append(opts, khttp.Address(c.HTTP.Addr))
	}
	srv := khttp.NewServer(opts...)

	v1 := srv.Route("/api/v1")
	v1.GET("/health", health.Health)
	srv.Handle("/api/v1/protected/session:probe", nethttp.HandlerFunc(protectedSessionProbe))
	fidesbffv1pb.RegisterFidesBffAuthServiceHTTPServer(srv, auth)
	fidesbffv1pb.RegisterFidesBffPricingServiceHTTPServer(srv, pricing)
	fidesbffv1pb.RegisterFidesBffLoanApplicationServiceHTTPServer(srv, origination)
	fidesbffv1pb.RegisterFidesBffIdentityProfileServiceHTTPServer(srv, identityProfile)
	return srv
}

func compatRequestDecoder(r *nethttp.Request, v any) error {
	message, ok := v.(proto.Message)
	if !ok {
		return khttp.DefaultRequestDecoder(r, v)
	}
	data, err := io.ReadAll(r.Body)
	r.Body = io.NopCloser(bytes.NewBuffer(data))
	if err != nil {
		return invalidRequestBodyError()
	}
	if len(data) == 0 {
		return nil
	}
	if err := (protojson.UnmarshalOptions{DiscardUnknown: true}).Unmarshal(data, message); err != nil {
		return invalidRequestBodyError()
	}
	return nil
}

func invalidRequestBodyError() *bffkit.HTTPError {
	return bffkit.ValidationError([]bffkit.FieldError{{Field: "body", Message: "invalid request body"}})
}

func protectedPathAuthFilter(tokenValidator bffkit.TokenValidator) khttp.FilterFunc {
	return func(next nethttp.Handler) nethttp.Handler {
		return nethttp.HandlerFunc(func(w nethttp.ResponseWriter, r *nethttp.Request) {
			if isProtectedPath(r.URL.Path) {
				bffkit.AuthFilter(tokenValidator)(next).ServeHTTP(w, r)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func isProtectedPath(path string) bool {
	return strings.HasPrefix(path, "/api/v1/protected/") ||
		strings.HasPrefix(path, "/api/v1/pricing/") ||
		path == "/api/v1/loan-applications" ||
		strings.HasPrefix(path, "/api/v1/loan-applications/") ||
		path == "/api/v1/me/identity-profile"
}

func protectedSessionProbe(w nethttp.ResponseWriter, r *nethttp.Request) {
	if r.Method != nethttp.MethodPost {
		w.WriteHeader(nethttp.StatusMethodNotAllowed)
		return
	}
	principal, ok := bffkit.PrincipalFromContext(r.Context())
	if !ok {
		bffkit.ErrorEncoder(w, r, bffkit.UnauthorizedError())
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(nethttp.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{"applicantId": principal.ApplicantID})
}

func compatResponseEncoder(w nethttp.ResponseWriter, r *nethttp.Request, v any) error {
	switch reply := v.(type) {
	case *fidesbffv1pb.FidesBffIdentityProfileServiceUpsertIdentityProfileResponse:
		return writeJSON(w, identityProfileUpsertJSON{
			Profile:     identityProfileJSON(reply.GetProfile()),
			CurrentStep: applicationStepJSON(reply.GetCurrentStep().String()),
		})
	case *fidesbffv1pb.FidesBffIdentityProfileServiceGetIdentityProfileResponse:
		body := identityProfileGetJSON{Empty: reply.GetEmpty()}
		if reply.GetProfile() != nil {
			profile := identityProfileJSON(reply.GetProfile())
			body.Profile = &profile
		}
		return writeJSON(w, body)
	default:
		if message, ok := v.(proto.Message); ok {
			data, err := (protojson.MarshalOptions{EmitUnpopulated: true}).Marshal(message)
			if err != nil {
				return err
			}
			w.Header().Set("Content-Type", "application/json")
			_, err = w.Write(data)
			return err
		}
		return khttp.DefaultResponseEncoder(w, r, v)
	}
}

type identityProfileUpsertJSON struct {
	Profile     identityProfileResponseJSON `json:"profile"`
	CurrentStep string                      `json:"currentStep"`
}

type identityProfileGetJSON struct {
	Empty   bool                         `json:"empty"`
	Profile *identityProfileResponseJSON `json:"profile,omitempty"`
}

type identityProfileResponseJSON struct {
	HKIDBody       string `json:"hkidBody"`
	HKIDCheckDigit string `json:"hkidCheckDigit"`
	FirstName      string `json:"firstName"`
	LastName       string `json:"lastName"`
	ChineseName    string `json:"chineseName"`
	Nationality    string `json:"nationality"`
	DateOfBirth    string `json:"dateOfBirth"`
}

func identityProfileJSON(profile *fidesbffv1pb.FidesBffIdentityProfile) identityProfileResponseJSON {
	if profile == nil {
		return identityProfileResponseJSON{}
	}
	return identityProfileResponseJSON{
		HKIDBody:       profile.GetHkidBody(),
		HKIDCheckDigit: profile.GetHkidCheckDigit(),
		FirstName:      profile.GetFirstName(),
		LastName:       profile.GetLastName(),
		ChineseName:    profile.GetChineseName(),
		Nationality:    nationalityJSON(profile.GetNationality().String()),
		DateOfBirth:    profile.GetDateOfBirth(),
	}
}

func writeJSON(w nethttp.ResponseWriter, v any) error {
	w.Header().Set("Content-Type", "application/json")
	return json.NewEncoder(w).Encode(v)
}

func nationalityJSON(value string) string {
	switch value {
	case "NATIONALITY_CHINESE":
		return "chinese"
	case "NATIONALITY_HONG_KONG":
		return "hong_kong"
	case "NATIONALITY_BRITISH":
		return "british"
	case "NATIONALITY_INDIAN":
		return "indian"
	case "NATIONALITY_FILIPINO":
		return "filipino"
	case "NATIONALITY_INDONESIAN":
		return "indonesian"
	case "NATIONALITY_PAKISTANI":
		return "pakistani"
	case "NATIONALITY_AMERICAN":
		return "american"
	case "NATIONALITY_AUSTRALIAN":
		return "australian"
	case "NATIONALITY_CANADIAN":
		return "canadian"
	case "NATIONALITY_OTHER":
		return "other"
	default:
		return ""
	}
}

func applicationStepJSON(value string) string {
	switch value {
	case "APPLICATION_STEP_LOAN_REQUEST":
		return "loan_request"
	case "APPLICATION_STEP_IDENTITY_INFORMATION":
		return "identity_information"
	default:
		return ""
	}
}
