package server

import (
	"encoding/json"
	nethttp "net/http"
	"strings"

	"github.com/go-kratos/kratos/v2/log"
	"github.com/go-kratos/kratos/v2/transport/http"
	fidesbffv1pb "github.com/spark-harness/idl-go-repo/vesta/lendora/fides-bff/v1"
	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/conf"
	"github.com/spark/fides-bff/internal/service"
)

// NewHTTPServer builds the REST transport and registers the /api/v1 routes.
func NewHTTPServer(
	c *conf.Server,
	health *service.HealthService,
	auth *service.AuthService,
	tokenValidator bffkit.TokenValidator,
	store bffkit.IdempotencyStore,
	logger log.Logger,
) *http.Server {
	opts := []http.ServerOption{
		http.ErrorEncoder(bffkit.ErrorEncoder),
		http.Filter(
			bffkit.TraceFilter(log.NewHelper(logger)),
			bffkit.CORSFilter(bffkit.CORSConfig{AllowedOrigins: c.CORS.AllowedOrigins, MaxAgeSec: 600}),
			protectedPathAuthFilter(tokenValidator),
			bffkit.IdempotencyFilter(store),
		),
	}
	if c.HTTP.Network != "" {
		opts = append(opts, http.Network(c.HTTP.Network))
	}
	if c.HTTP.Addr != "" {
		opts = append(opts, http.Address(c.HTTP.Addr))
	}
	srv := http.NewServer(opts...)

	v1 := srv.Route("/api/v1")
	v1.GET("/health", health.Health)
	srv.Handle("/api/v1/protected/session:probe", nethttp.HandlerFunc(protectedSessionProbe))
	fidesbffv1pb.RegisterFidesBffAuthServiceHTTPServer(srv, auth)
	return srv
}

func protectedPathAuthFilter(tokenValidator bffkit.TokenValidator) http.FilterFunc {
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
		strings.HasPrefix(path, "/api/v1/loan-applications/")
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
