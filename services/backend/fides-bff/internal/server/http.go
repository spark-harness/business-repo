package server

import (
	"github.com/go-kratos/kratos/v2/transport/http"

	"github.com/spark/fides-bff/internal/conf"
	"github.com/spark/fides-bff/internal/service"
)

// NewHTTPServer builds the REST transport and registers the /api/v1 routes.
//
// All front-end facing routes live under the /api/v1 prefix (BR1). Cross-cutting
// middleware (error envelope, idempotency, observability) is registered here in
// later tasks (T2-T4); T1 wires only the health endpoint.
func NewHTTPServer(c *conf.Server, health *service.HealthService) *http.Server {
	var opts []http.ServerOption
	if c.HTTP.Network != "" {
		opts = append(opts, http.Network(c.HTTP.Network))
	}
	if c.HTTP.Addr != "" {
		opts = append(opts, http.Address(c.HTTP.Addr))
	}
	srv := http.NewServer(opts...)

	v1 := srv.Route("/api/v1")
	v1.GET("/health", health.Health)
	return srv
}
