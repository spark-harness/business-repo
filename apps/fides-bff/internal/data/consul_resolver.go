package data

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/spark/fides-bff/internal/conf"
)

type ConsulResolver struct {
	baseURL     string
	serviceName string
	client      *http.Client
}

func NewConsulResolver(c *conf.Applicant) *ConsulResolver {
	consul := conf.Consul{}
	if c != nil {
		consul = c.Consul
	}
	scheme := firstNonEmpty(consul.Scheme, "http")
	address := firstNonEmpty(consul.Address, "127.0.0.1:8500")
	return &ConsulResolver{
		baseURL:     scheme + "://" + address,
		serviceName: firstNonEmpty(consul.ServiceName, "applicant-api"),
		client:      &http.Client{Timeout: 2 * time.Second},
	}
}

func (r *ConsulResolver) Resolve(ctx context.Context) (string, error) {
	if r == nil {
		return "", errors.New("consul resolver is not configured")
	}
	endpoint, err := url.JoinPath(r.baseURL, "/v1/health/service", r.serviceName)
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint+"?passing=true", nil)
	if err != nil {
		return "", err
	}
	resp, err := r.client.Do(req)
	if err != nil {
		return "", err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("consul health status %d", resp.StatusCode)
	}

	var entries []consulHealthEntry
	if err := json.NewDecoder(resp.Body).Decode(&entries); err != nil {
		return "", err
	}
	for _, entry := range entries {
		address := firstNonEmpty(entry.Service.Address, entry.Node.Address)
		port := entry.Service.grpcPort()
		if address == "" || port == 0 {
			continue
		}
		return net.JoinHostPort(address, fmt.Sprintf("%d", port)), nil
	}
	return "", errNoHealthyApplicant
}

type consulHealthEntry struct {
	Node    consulHealthNode    `json:"Node"`
	Service consulHealthService `json:"Service"`
}

type consulHealthNode struct {
	Address string `json:"Address"`
}

type consulHealthService struct {
	Address string            `json:"Address"`
	Port    int               `json:"Port"`
	Meta    map[string]string `json:"Meta"`
}

func (s consulHealthService) grpcPort() int {
	if raw := strings.TrimSpace(s.Meta["grpc_port"]); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			return parsed
		}
	}
	return s.Port
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}
