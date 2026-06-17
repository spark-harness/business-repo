package biz

import "context"

// Version is the build version of the service. It is a distinct type so wire can
// inject it unambiguously instead of treating it as a bare string dependency.
type Version string

// Health is the domain snapshot of service health returned by the BFF.
//
// For T1 (LEN-21) health is liveness only: the process is up and reports its
// build version. Downstream readiness (gRPC dependencies) is intentionally out
// of scope and arrives with the first business endpoint task.
type Health struct {
	Status  string
	Version string
}

// HealthUsecase reports the liveness of the BFF.
//
// It lives in biz (application + domain) and owns the rule of "what healthy
// means". The service layer only maps this to the REST protocol.
type HealthUsecase struct {
	version string
}

// NewHealthUsecase builds the health usecase with the running build version.
func NewHealthUsecase(version Version) *HealthUsecase {
	return &HealthUsecase{version: string(version)}
}

// Check returns the current health snapshot. The context is accepted for
// forward compatibility with readiness checks; T1 does not use it.
func (uc *HealthUsecase) Check(_ context.Context) Health {
	return Health{Status: "ok", Version: uc.version}
}
