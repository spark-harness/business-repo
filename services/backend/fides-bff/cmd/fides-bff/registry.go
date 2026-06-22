package main

import (
	"fmt"
	"net"
	"net/url"
	"strings"

	kratosconsul "github.com/go-kratos/kratos/contrib/registry/consul/v2"
	"github.com/go-kratos/kratos/v2/registry"
	"github.com/hashicorp/consul/api"

	"github.com/spark/fides-bff/internal/conf"
)

type registration struct {
	registrar registry.Registrar
	endpoint  *url.URL
	metadata  map[string]string
}

func newConsulRegistration(c *conf.Registry) (*registration, error) {
	if c == nil || !c.Consul.Enabled {
		return &registration{}, nil
	}
	cfg := c.Consul
	consulConfig := api.DefaultConfig()
	consulConfig.Scheme = firstNonEmpty(cfg.Scheme, "http")
	consulConfig.Address = firstNonEmpty(cfg.Address, "127.0.0.1:8500")
	client, err := api.NewClient(consulConfig)
	if err != nil {
		return nil, err
	}
	endpoint, err := discoveryEndpoint(cfg.DiscoveryAddr)
	if err != nil {
		return nil, err
	}
	opts := []kratosconsul.Option{
		kratosconsul.WithHeartbeat(cfg.Heartbeat),
		kratosconsul.WithHealthCheck(cfg.HealthCheck),
	}
	if cfg.HealthCheckIntervalSec > 0 {
		opts = append(opts, kratosconsul.WithHealthCheckInterval(cfg.HealthCheckIntervalSec))
	}
	if cfg.DeregisterAfterSec > 0 {
		opts = append(opts, kratosconsul.WithDeregisterCriticalServiceAfter(cfg.DeregisterAfterSec))
	}
	return &registration{
		registrar: kratosconsul.New(client, opts...),
		endpoint:  endpoint,
		metadata:  registryMetadata(c),
	}, nil
}

func registryMetadata(c *conf.Registry) map[string]string {
	if c == nil || len(c.Consul.Metadata) == 0 {
		return nil
	}
	metadata := make(map[string]string, len(c.Consul.Metadata))
	for key, value := range c.Consul.Metadata {
		metadata[key] = value
	}
	return metadata
}

func discoveryEndpoint(addr string) (*url.URL, error) {
	addr = strings.TrimSpace(addr)
	if addr == "" {
		return nil, fmt.Errorf("registry.consul.discovery_addr is required when Consul registration is enabled")
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		return nil, fmt.Errorf("registry.consul.discovery_addr must be a reachable host:port, got %q", addr)
	}
	return &url.URL{Scheme: "http", Host: net.JoinHostPort(host, port)}, nil
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}
