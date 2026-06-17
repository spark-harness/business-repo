package server

import (
	"github.com/go-kratos/kratos/v2/log"
	"github.com/go-kratos/kratos/v2/transport/http"
	"github.com/spark/bffkit"

	"github.com/spark/fides-bff/internal/conf"
	"github.com/spark/fides-bff/internal/service"
)

// NewHTTPServer builds the REST transport and registers the /api/v1 routes.
func NewHTTPServer(c *conf.Server, health *service.HealthService, store bffkit.IdempotencyStore, logger log.Logger) *http.Server {
	opts := []http.ServerOption{
		http.ErrorEncoder(bffkit.ErrorEncoder),
		http.Filter(
			bffkit.TraceFilter(log.NewHelper(logger)),
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
	return srv
}
