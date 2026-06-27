package bffkit

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestAuthFilter_missingBearerToken_returnsUnauthorizedEnvelope(t *testing.T) {
	handler := AuthFilter(fakeTokenValidator{})(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatal("protected handler should not be called")
	}))

	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/protected", nil))

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusUnauthorized, rec.Body.String())
	}
}

func TestAuthFilter_validTokenStoresPrincipalAndClearsExternalApplicantHeader(t *testing.T) {
	validator := fakeTokenValidator{principal: Principal{
		ApplicantID: "applicant_001",
		TokenID:     "token-1",
		ExpiresAt:   time.Now().Add(time.Hour),
	}}
	var got Principal
	var gotHeader string
	handler := AuthFilter(validator)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var ok bool
		got, ok = PrincipalFromContext(r.Context())
		if !ok {
			t.Fatal("missing principal")
		}
		gotHeader = r.Header.Get(HeaderApplicantID)
		w.WriteHeader(http.StatusNoContent)
	}))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/protected", nil)
	req.Header.Set("Authorization", "Bearer valid-token")
	req.Header.Set(HeaderApplicantID, "attacker")
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("status code = %d, want %d (body: %s)", rec.Code, http.StatusNoContent, rec.Body.String())
	}
	if got.ApplicantID != "applicant_001" {
		t.Fatalf("principal applicant id = %q, want applicant_001", got.ApplicantID)
	}
	if gotHeader != "" {
		t.Fatalf("external x-applicant-id reached handler: %q", gotHeader)
	}
}

func TestRequireResourceOwner_rejectsDifferentApplicant(t *testing.T) {
	ctx := ContextWithPrincipal(context.Background(), Principal{ApplicantID: "applicant_001"})

	err := RequireResourceOwner(ctx, "applicant_002")

	var httpErr *HTTPError
	if !errors.As(err, &httpErr) {
		t.Fatalf("err = %#v, want HTTPError", err)
	}
	if httpErr.Status != http.StatusForbidden || httpErr.Code != CodeForbidden {
		t.Fatalf("error = %#v, want forbidden", httpErr)
	}
}

type fakeTokenValidator struct {
	principal Principal
	err       error
}

func (v fakeTokenValidator) ValidateAccessToken(context.Context, string) (Principal, error) {
	if v.err != nil {
		return Principal{}, v.err
	}
	return v.principal, nil
}
