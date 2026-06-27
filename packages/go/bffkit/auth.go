package bffkit

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"
)

const HeaderApplicantID = "X-Applicant-Id"

const authorizationBearerPrefix = "Bearer "

var ErrInvalidAccessToken = errors.New("invalid access token")

type Principal struct {
	ApplicantID string
	TokenID     string
	ExpiresAt   time.Time
}

type TokenValidator interface {
	ValidateAccessToken(context.Context, string) (Principal, error)
}

const principalKey contextKey = "bffkit.principal"

func ContextWithPrincipal(ctx context.Context, principal Principal) context.Context {
	return context.WithValue(ctx, principalKey, principal)
}

func PrincipalFromContext(ctx context.Context) (Principal, bool) {
	principal, ok := ctx.Value(principalKey).(Principal)
	return principal, ok && principal.ApplicantID != ""
}

func AuthFilter(validator TokenValidator) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, ok := bearerToken(r.Header.Get("Authorization"))
			if !ok || validator == nil {
				ErrorEncoder(w, r, UnauthorizedError())
				return
			}
			principal, err := validator.ValidateAccessToken(r.Context(), token)
			if err != nil || principal.ApplicantID == "" {
				ErrorEncoder(w, r, UnauthorizedError())
				return
			}
			cloned := r.Clone(ContextWithPrincipal(r.Context(), principal))
			cloned.Header.Del(HeaderApplicantID)
			next.ServeHTTP(w, cloned)
		})
	}
}

func UnauthorizedError() *HTTPError {
	return &HTTPError{Status: http.StatusUnauthorized, Code: CodeUnauthorized, Message: "authentication required"}
}

func ForbiddenError() *HTTPError {
	return &HTTPError{Status: http.StatusForbidden, Code: CodeForbidden, Message: "permission denied"}
}

func RequireResourceOwner(ctx context.Context, resourceApplicantID string) error {
	principal, ok := PrincipalFromContext(ctx)
	if !ok {
		return UnauthorizedError()
	}
	if principal.ApplicantID != resourceApplicantID {
		return ForbiddenError()
	}
	return nil
}

func bearerToken(header string) (string, bool) {
	if !strings.HasPrefix(header, authorizationBearerPrefix) {
		return "", false
	}
	token := strings.TrimSpace(strings.TrimPrefix(header, authorizationBearerPrefix))
	return token, token != ""
}
