package bffkit

import (
	"net/http"
	"strconv"
	"strings"

	khttp "github.com/go-kratos/kratos/v2/transport/http"
)

type CORSConfig struct {
	AllowedOrigins   []string
	AllowedMethods   []string
	AllowedHeaders   []string
	ExposedHeaders   []string
	MaxAgeSec        int
	AllowCredentials bool
}

func CORSFilter(config CORSConfig) khttp.FilterFunc {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			allowedOrigin, ok := allowedCORSOrigin(origin, config.AllowedOrigins)
			if origin == "" || !ok {
				next.ServeHTTP(w, r)
				return
			}

			setCORSHeaders(w.Header(), allowedOrigin, config, r.Header.Get("Access-Control-Request-Headers"))
			if r.Method == http.MethodOptions && r.Header.Get("Access-Control-Request-Method") != "" {
				w.WriteHeader(http.StatusNoContent)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func setCORSHeaders(header http.Header, origin string, config CORSConfig, requestedHeaders string) {
	header.Set("Access-Control-Allow-Origin", origin)
	if origin != "*" {
		header.Add("Vary", "Origin")
	}
	if config.AllowCredentials {
		header.Set("Access-Control-Allow-Credentials", "true")
	}
	header.Set("Access-Control-Allow-Methods", strings.Join(corsValues(config.AllowedMethods, []string{http.MethodGet, http.MethodPost, http.MethodOptions}), ", "))
	header.Set("Access-Control-Allow-Headers", corsAllowHeaders(config, requestedHeaders))
	header.Set("Access-Control-Expose-Headers", strings.Join(corsValues(config.ExposedHeaders, []string{HeaderTraceID, HeaderCorrelationID, "Retry-After"}), ", "))
	if config.MaxAgeSec > 0 {
		header.Set("Access-Control-Max-Age", strconv.Itoa(config.MaxAgeSec))
	}
}

func corsAllowHeaders(config CORSConfig, requestedHeaders string) string {
	if len(config.AllowedHeaders) > 0 {
		return strings.Join(corsValues(config.AllowedHeaders, nil), ", ")
	}
	if strings.TrimSpace(requestedHeaders) != "" {
		return requestedHeaders
	}
	return strings.Join([]string{"Content-Type", HeaderIdempotencyKey, HeaderTraceParent, HeaderTraceState, HeaderBaggage, HeaderTraceID, HeaderCorrelationID}, ", ")
}

func allowedCORSOrigin(origin string, allowed []string) (string, bool) {
	if strings.TrimSpace(origin) == "" {
		return "", false
	}
	for _, candidate := range allowed {
		candidate = strings.TrimSpace(candidate)
		if candidate == "*" {
			return "*", true
		}
		if candidate == origin {
			return origin, true
		}
	}
	return "", false
}

func corsValues(values []string, defaults []string) []string {
	cleaned := make([]string, 0, len(values))
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			cleaned = append(cleaned, strings.TrimSpace(value))
		}
	}
	if len(cleaned) > 0 {
		return cleaned
	}
	return defaults
}
