package service

import (
	nethttp "net/http"

	"github.com/go-kratos/kratos/v3/transport/http"

	"github.com/spark/fides-bff/internal/biz"
)

// HealthResponse is the REST response body for GET /api/v1/health.
//
// It is an adapter/inbound DTO: the domain biz.Health is mapped here so the
// wire contract stays decoupled from the domain model.
type HealthResponse struct {
	Status  string `json:"status"`
	Version string `json:"version"`
}

// HealthService adapts the health usecase to the REST protocol.
type HealthService struct {
	uc *biz.HealthUsecase
}

// NewHealthService builds the health adapter.
func NewHealthService(uc *biz.HealthUsecase) *HealthService {
	return &HealthService{uc: uc}
}

// Health handles GET /api/v1/health and returns the service status and version.
func (s *HealthService) Health(ctx http.Context) error {
	h := s.uc.Check(ctx)
	return ctx.JSON(nethttp.StatusOK, HealthResponse{Status: h.Status, Version: h.Version})
}
