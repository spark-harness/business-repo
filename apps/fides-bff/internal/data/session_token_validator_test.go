package data

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"strconv"
	"testing"
	"time"

	"github.com/spark/fides-bff/internal/conf"
)

func TestSessionTokenValidator_validateHMACAccessToken(t *testing.T) {
	now := time.UnixMilli(1700000000000)
	validator := NewSessionTokenValidator(&conf.Auth{
		TokenMode:      "hmac",
		TokenSecret:    "secret",
		AccessTokenTTL: "1h",
	})
	validator.now = func() time.Time { return now.Add(time.Minute) }

	principal, err := validator.ValidateAccessToken(context.Background(), hmacAccessToken("secret", "applicant_001", now, "token-1"))

	if err != nil {
		t.Fatalf("ValidateAccessToken() error = %v", err)
	}
	if principal.ApplicantID != "applicant_001" || principal.TokenID != "token-1" {
		t.Fatalf("principal = %#v", principal)
	}
}

func TestSessionTokenValidator_rejectsExpiredHMACToken(t *testing.T) {
	now := time.UnixMilli(1700000000000)
	validator := NewSessionTokenValidator(&conf.Auth{
		TokenMode:      "hmac",
		TokenSecret:    "secret",
		AccessTokenTTL: "1h",
	})
	validator.now = func() time.Time { return now.Add(2 * time.Hour) }

	_, err := validator.ValidateAccessToken(context.Background(), hmacAccessToken("secret", "applicant_001", now, "token-1"))

	if err == nil {
		t.Fatal("ValidateAccessToken() error = nil")
	}
}

func hmacAccessToken(secret string, applicantID string, issuedAt time.Time, tokenID string) string {
	payload := base64.RawURLEncoding.EncodeToString([]byte("access:" + applicantID + ":" + strconv.FormatInt(issuedAt.UnixMilli(), 10) + ":" + tokenID))
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(payload))
	return "access_v1." + payload + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
