package data

import (
	"context"
	"errors"
	"strings"
)

func grpcResolver(target string, fallback ServiceResolver) ServiceResolver {
	if strings.TrimSpace(target) != "" {
		return directGRPCTargetResolver(strings.TrimSpace(target))
	}
	return fallback
}

type directGRPCTargetResolver string

func (r directGRPCTargetResolver) Resolve(context.Context) (string, error) {
	if strings.TrimSpace(string(r)) == "" {
		return "", errors.New("grpc target is not configured")
	}
	return string(r), nil
}
