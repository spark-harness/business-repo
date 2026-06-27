package data

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"strings"
	"time"

	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/conf"
)

type SessionTokenValidator struct {
	mode           string
	secret         string
	accessTokenTTL time.Duration
	now            func() time.Time
}

func NewSessionTokenValidator(c *conf.Auth) *SessionTokenValidator {
	ttl := time.Hour
	mode := "hmac"
	secret := ""
	if c != nil {
		if c.TokenMode != "" {
			mode = c.TokenMode
		}
		secret = c.TokenSecret
		if parsed, err := time.ParseDuration(c.AccessTokenTTL); err == nil && parsed > 0 {
			ttl = parsed
		}
	}
	return &SessionTokenValidator{mode: mode, secret: secret, accessTokenTTL: ttl, now: time.Now}
}

func (v *SessionTokenValidator) ValidateAccessToken(_ context.Context, token string) (bffkit.Principal, error) {
	if v == nil {
		return bffkit.Principal{}, bffkit.ErrInvalidAccessToken
	}
	switch strings.ToLower(v.mode) {
	case "simple":
		return v.validateSimple(token)
	case "hmac":
		return v.validateHMAC(token)
	default:
		return bffkit.Principal{}, bffkit.ErrInvalidAccessToken
	}
}

func (v *SessionTokenValidator) validateSimple(token string) (bffkit.Principal, error) {
	parts := strings.Split(token, "_")
	if len(parts) < 4 || parts[0] != "access" {
		return bffkit.Principal{}, bffkit.ErrInvalidAccessToken
	}
	issuedAt, err := parseMillis(parts[len(parts)-2])
	if err != nil {
		return bffkit.Principal{}, bffkit.ErrInvalidAccessToken
	}
	expiresAt := issuedAt.Add(v.accessTokenTTL)
	if !v.now().Before(expiresAt) {
		return bffkit.Principal{}, bffkit.ErrInvalidAccessToken
	}
	return bffkit.Principal{
		ApplicantID: strings.Join(parts[1:len(parts)-2], "_"),
		TokenID:     parts[len(parts)-1],
		ExpiresAt:   expiresAt,
	}, nil
}

func (v *SessionTokenValidator) validateHMAC(token string) (bffkit.Principal, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 || parts[0] != "access_v1" || v.secret == "" {
		return bffkit.Principal{}, bffkit.ErrInvalidAccessToken
	}
	if !hmac.Equal([]byte(v.sign(parts[1])), []byte(parts[2])) {
		return bffkit.Principal{}, bffkit.ErrInvalidAccessToken
	}
	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return bffkit.Principal{}, bffkit.ErrInvalidAccessToken
	}
	payload := strings.Split(string(payloadBytes), ":")
	if len(payload) != 4 || payload[0] != "access" {
		return bffkit.Principal{}, bffkit.ErrInvalidAccessToken
	}
	issuedAt, err := parseMillis(payload[2])
	if err != nil {
		return bffkit.Principal{}, bffkit.ErrInvalidAccessToken
	}
	expiresAt := issuedAt.Add(v.accessTokenTTL)
	if !v.now().Before(expiresAt) {
		return bffkit.Principal{}, bffkit.ErrInvalidAccessToken
	}
	return bffkit.Principal{ApplicantID: payload[1], TokenID: payload[3], ExpiresAt: expiresAt}, nil
}

func (v *SessionTokenValidator) sign(payload string) string {
	mac := hmac.New(sha256.New, []byte(v.secret))
	_, _ = mac.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func parseMillis(value string) (time.Time, error) {
	var millis int64
	for _, char := range value {
		if char < '0' || char > '9' {
			return time.Time{}, errors.New("invalid millis")
		}
		millis = millis*10 + int64(char-'0')
	}
	return time.UnixMilli(millis), nil
}
