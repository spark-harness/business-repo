package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/spark/fides-bff/internal/conf"
)

func TestLoadBootstrap_DefaultConfigOnly(t *testing.T) {
	configPath := writeConfig(t, `
server:
  http:
    network: tcp
    addr: 0.0.0.0:8000
applicant:
  consul:
    address: 127.0.0.1:8500
    scheme: http
    service_name: applicant-api
  grpc:
    timeout: 3s
    plaintext: true
registry:
  consul:
    enabled: false
observability:
  otel:
    enabled: false
    exporter: otlp
    protocol: http/protobuf
    environment: local
    release: dev
`)

	got, err := loadBootstrap(loadConfigOptions{ConfigPath: configPath, EnvFilePath: filepath.Join(t.TempDir(), ".env")})
	if err != nil {
		t.Fatalf("loadBootstrap() error = %v", err)
	}
	if got.Server.HTTP.Addr != "0.0.0.0:8000" {
		t.Fatalf("Server.HTTP.Addr = %q", got.Server.HTTP.Addr)
	}
	if got.Applicant.GRPC.Timeout != "3s" {
		t.Fatalf("Applicant.GRPC.Timeout = %q", got.Applicant.GRPC.Timeout)
	}
}

func TestLoadBootstrap_EnvFileDoesNotOverrideExistingEnvironment(t *testing.T) {
	configPath := writeConfig(t, placeholderConfig())
	envPath := writeFile(t, ".env", `
SERVER_HTTP_ADDR=127.0.0.1:9000
APPLICANT_GRPC_TIMEOUT=10s
REGISTRY_CONSUL_SERVICE_NAME=env-file-fides-bff
QUOTE_HTTP_BASE_URL=http://quote-api.local:8080
QUOTE_HTTP_TIMEOUT=5s
ORIGINATION_HTTP_BASE_URL=http://origination-api.local:8080
ORIGINATION_HTTP_TIMEOUT=6s
`)
	t.Setenv("SERVER_HTTP_ADDR", "127.0.0.1:7000")
	t.Setenv("REGISTRY_CONSUL_SERVICE_NAME", "shell-fides-bff")
	cleanupEnv(t, "APPLICANT_GRPC_TIMEOUT")
	cleanupEnv(t, "QUOTE_HTTP_BASE_URL", "QUOTE_HTTP_TIMEOUT")
	cleanupEnv(t, "ORIGINATION_HTTP_BASE_URL", "ORIGINATION_HTTP_TIMEOUT")

	got, err := loadBootstrap(loadConfigOptions{ConfigPath: configPath, EnvFilePath: envPath})
	if err != nil {
		t.Fatalf("loadBootstrap() error = %v", err)
	}
	if got.Server.HTTP.Addr != "127.0.0.1:7000" {
		t.Fatalf("Server.HTTP.Addr = %q", got.Server.HTTP.Addr)
	}
	if got.Applicant.GRPC.Timeout != "10s" {
		t.Fatalf("Applicant.GRPC.Timeout = %q", got.Applicant.GRPC.Timeout)
	}
	if got.Registry.Consul.ServiceName != "shell-fides-bff" {
		t.Fatalf("Registry.Consul.ServiceName = %q", got.Registry.Consul.ServiceName)
	}
	if got.Quote.HTTP.BaseURL != "http://quote-api.local:8080" || got.Quote.HTTP.Timeout != "5s" {
		t.Fatalf("Quote.HTTP = %#v", got.Quote.HTTP)
	}
	if got.Origination.HTTP.BaseURL != "http://origination-api.local:8080" || got.Origination.HTTP.Timeout != "6s" {
		t.Fatalf("Origination.HTTP = %#v", got.Origination.HTTP)
	}
}

func TestLoadBootstrap_KratosFileEnvPlaceholders(t *testing.T) {
	configPath := writeConfig(t, placeholderConfig())
	t.Setenv("SERVER_HTTP_ADDR", "127.0.0.1:7000")
	t.Setenv("SERVER_CORS_ALLOWED_ORIGIN_0", "http://localhost:5173")
	t.Setenv("REGISTRY_CONSUL_SERVICE_NAME", "dev-1-fides-bff")
	t.Setenv("REGISTRY_CONSUL_HEALTH_CHECK_INTERVAL_SEC", "25")
	t.Setenv("OBSERVABILITY_OTEL_ENABLED", "true")
	t.Setenv("OBSERVABILITY_OTEL_ENDPOINT", "http://otel.local/v1/traces")

	got, err := loadBootstrap(loadConfigOptions{ConfigPath: configPath, EnvFilePath: filepath.Join(t.TempDir(), ".env")})
	if err != nil {
		t.Fatalf("loadBootstrap() error = %v", err)
	}
	if got.Server.HTTP.Addr != "127.0.0.1:7000" {
		t.Fatalf("Server.HTTP.Addr = %q", got.Server.HTTP.Addr)
	}
	if len(got.Server.CORS.AllowedOrigins) != 2 || got.Server.CORS.AllowedOrigins[0] != "http://localhost:5173" {
		t.Fatalf("Server.CORS.AllowedOrigins = %#v", got.Server.CORS.AllowedOrigins)
	}
	if got.Registry.Consul.ServiceName != "dev-1-fides-bff" {
		t.Fatalf("Registry.Consul.ServiceName = %q", got.Registry.Consul.ServiceName)
	}
	if got.Registry.Consul.HealthCheckIntervalSec != 25 {
		t.Fatalf("Registry.Consul.HealthCheckIntervalSec = %d", got.Registry.Consul.HealthCheckIntervalSec)
	}
	if !got.Observability.OTel.Enabled || got.Observability.OTel.Endpoint != "http://otel.local/v1/traces" {
		t.Fatalf("Observability.OTel = %#v", got.Observability.OTel)
	}
}

func TestLoadBootstrap_ConfigConsulEnvironmentDoesNotLoadRemoteConfig(t *testing.T) {
	configPath := writeConfig(t, placeholderConfig())
	t.Setenv("CONFIG_CONSUL_ENABLED", "true")
	t.Setenv("CONFIG_CONSUL_ADDRESS", "127.0.0.1:1")
	t.Setenv("CONFIG_CONSUL_SCHEME", "http")
	t.Setenv("CONFIG_CONSUL_PATH", "config/lendora/fides-bff/config.yaml")

	got, err := loadBootstrap(loadConfigOptions{ConfigPath: configPath, EnvFilePath: filepath.Join(t.TempDir(), ".env")})
	if err != nil {
		t.Fatalf("loadBootstrap() error = %v", err)
	}
	if got.Server.HTTP.Addr != "0.0.0.0:8000" {
		t.Fatalf("Server.HTTP.Addr = %q", got.Server.HTTP.Addr)
	}
	if got.Registry.Consul.ServiceName != "fides-bff" {
		t.Fatalf("Registry.Consul.ServiceName = %q", got.Registry.Consul.ServiceName)
	}
}

func TestLoadBootstrap_RequiresTokenSecretForHMAC(t *testing.T) {
	configPath := writeConfig(t, `
auth:
  token_mode: hmac
  token_secret: ""
observability:
  otel:
    enabled: false
`)

	_, err := loadBootstrap(loadConfigOptions{ConfigPath: configPath, EnvFilePath: filepath.Join(t.TempDir(), ".env")})
	if err == nil {
		t.Fatal("expected missing AUTH_TOKEN_SECRET error")
	}
	if !strings.Contains(err.Error(), "AUTH_TOKEN_SECRET") {
		t.Fatalf("error = %v", err)
	}
}

func minimalConfig(addr string) string {
	return fmt.Sprintf(`
server:
  http:
    network: tcp
    addr: %s
applicant:
  consul:
    address: 127.0.0.1:8500
    scheme: http
    service_name: applicant-api
  grpc:
    timeout: 3s
    plaintext: true
quote:
  consul:
    address: 127.0.0.1:8500
    scheme: http
    service_name: quote-api
  http:
    base_url: ""
    timeout: 3s
origination:
  consul:
    address: 127.0.0.1:8500
    scheme: http
    service_name: origination-api
  http:
    base_url: ""
    timeout: 3s
registry:
  consul:
    enabled: false
observability:
  otel:
    enabled: false
    exporter: otlp
    endpoint: ""
    protocol: http/protobuf
    headers: {}
    environment: local
    release: dev
`, addr)
}

func placeholderConfig() string {
	return `
server:
  http:
    network: "${SERVER_HTTP_NETWORK:tcp}"
    addr: "${SERVER_HTTP_ADDR:0.0.0.0:8000}"
  cors:
    allowed_origins:
      - "${SERVER_CORS_ALLOWED_ORIGIN_0:http://localhost:3000}"
      - "${SERVER_CORS_ALLOWED_ORIGIN_1:http://localhost:3001}"
applicant:
  consul:
    address: "${APPLICANT_CONSUL_ADDRESS:127.0.0.1:8500}"
    scheme: "${APPLICANT_CONSUL_SCHEME:http}"
    service_name: "${APPLICANT_CONSUL_SERVICE_NAME:applicant-api}"
  grpc:
    timeout: "${APPLICANT_GRPC_TIMEOUT:3s}"
    plaintext: "${APPLICANT_GRPC_PLAINTEXT:true}"
quote:
  consul:
    address: "${QUOTE_CONSUL_ADDRESS:127.0.0.1:8500}"
    scheme: "${QUOTE_CONSUL_SCHEME:http}"
    service_name: "${QUOTE_CONSUL_SERVICE_NAME:quote-api}"
  http:
    base_url: "${QUOTE_HTTP_BASE_URL:}"
    timeout: "${QUOTE_HTTP_TIMEOUT:3s}"
origination:
  consul:
    address: "${ORIGINATION_CONSUL_ADDRESS:127.0.0.1:8500}"
    scheme: "${ORIGINATION_CONSUL_SCHEME:http}"
    service_name: "${ORIGINATION_CONSUL_SERVICE_NAME:origination-api}"
  http:
    base_url: "${ORIGINATION_HTTP_BASE_URL:}"
    timeout: "${ORIGINATION_HTTP_TIMEOUT:3s}"
registry:
  consul:
    enabled: "${REGISTRY_CONSUL_ENABLED:false}"
    address: "${REGISTRY_CONSUL_ADDRESS:127.0.0.1:8500}"
    scheme: "${REGISTRY_CONSUL_SCHEME:http}"
    service_name: "${REGISTRY_CONSUL_SERVICE_NAME:fides-bff}"
    discovery_addr: "${REGISTRY_CONSUL_DISCOVERY_ADDR:127.0.0.1:8000}"
    heartbeat: "${REGISTRY_CONSUL_HEARTBEAT:true}"
    health_check: "${REGISTRY_CONSUL_HEALTH_CHECK:true}"
    health_check_interval_sec: "${REGISTRY_CONSUL_HEALTH_CHECK_INTERVAL_SEC:10}"
    deregister_after_sec: "${REGISTRY_CONSUL_DEREGISTER_AFTER_SEC:60}"
    metadata:
      module: "${REGISTRY_CONSUL_METADATA_MODULE:frontend}"
observability:
  otel:
    enabled: "${OBSERVABILITY_OTEL_ENABLED:false}"
    exporter: "${OBSERVABILITY_OTEL_EXPORTER:otlp}"
    endpoint: "${OBSERVABILITY_OTEL_ENDPOINT:}"
    protocol: "${OBSERVABILITY_OTEL_PROTOCOL:http/protobuf}"
    headers:
      x-sentry-auth: "${OBSERVABILITY_OTEL_X_SENTRY_AUTH:}"
    environment: "${OBSERVABILITY_OTEL_ENVIRONMENT:local}"
    release: "${OBSERVABILITY_OTEL_RELEASE:dev}"
`
}

func writeConfig(t *testing.T, data string) string {
	t.Helper()
	return writeFile(t, "config.yaml", data)
}

func writeFile(t *testing.T, name string, data string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, []byte(strings.TrimSpace(data)+"\n"), 0o600); err != nil {
		t.Fatalf("write test file: %v", err)
	}
	return path
}

func cleanupEnv(t *testing.T, keys ...string) {
	t.Helper()
	for _, key := range keys {
		key := key
		original, hadOriginal := os.LookupEnv(key)
		t.Cleanup(func() {
			if hadOriginal {
				_ = os.Setenv(key, original)
				return
			}
			_ = os.Unsetenv(key)
		})
	}
}

var _ = conf.Bootstrap{}
