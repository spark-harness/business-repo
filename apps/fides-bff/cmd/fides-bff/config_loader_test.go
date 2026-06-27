package main

import (
	"encoding/base64"
	"fmt"
	"net/http"
	"net/http/httptest"
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
	configPath := writeConfig(t, minimalConfig("0.0.0.0:8000"))
	envPath := writeFile(t, ".env", `
SERVER_HTTP_ADDR=127.0.0.1:9000
APPLICANT_GRPC_TIMEOUT=10s
QUOTE_HTTP_BASE_URL=http://quote-api.local:8080
QUOTE_HTTP_TIMEOUT=5s
ORIGINATION_HTTP_BASE_URL=http://origination-api.local:8080
ORIGINATION_HTTP_TIMEOUT=6s
`)
	t.Setenv("SERVER_HTTP_ADDR", "127.0.0.1:7000")
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
	if got.Quote.HTTP.BaseURL != "http://quote-api.local:8080" || got.Quote.HTTP.Timeout != "5s" {
		t.Fatalf("Quote.HTTP = %#v", got.Quote.HTTP)
	}
	if got.Origination.HTTP.BaseURL != "http://origination-api.local:8080" || got.Origination.HTTP.Timeout != "6s" {
		t.Fatalf("Origination.HTTP = %#v", got.Origination.HTTP)
	}
}

func TestLoadBootstrap_AllowlistIgnoresUnrelatedEnvironment(t *testing.T) {
	configPath := writeConfig(t, minimalConfig("0.0.0.0:8000"))
	t.Setenv("SERVER_HTTP_ADDR", "127.0.0.1:7000")
	t.Setenv("PATH", "this-must-not-enter-config")
	t.Setenv("SECRET_TOKEN", "super-secret-token")

	got, err := loadBootstrap(loadConfigOptions{ConfigPath: configPath, EnvFilePath: filepath.Join(t.TempDir(), ".env")})
	if err != nil {
		t.Fatalf("loadBootstrap() error = %v", err)
	}
	if got.Server.HTTP.Addr != "127.0.0.1:7000" {
		t.Fatalf("Server.HTTP.Addr = %q", got.Server.HTTP.Addr)
	}
	if got.Observability.OTel.Endpoint != "" {
		t.Fatalf("unexpected unrelated env in config: %+v", got.Observability.OTel)
	}
}

func TestLoadBootstrap_ConsulOverridesFileAndEnvironment(t *testing.T) {
	configPath := writeConfig(t, minimalConfig("0.0.0.0:8000"))
	t.Setenv("SERVER_HTTP_ADDR", "127.0.0.1:7000")
	consul := newConsulKVServer(t, map[string]string{
		"config/lendora/fides-bff/config.yaml": `
server:
  http:
    addr: 127.0.0.1:9100
applicant:
  grpc:
    timeout: 12s
`,
	})
	t.Setenv("CONFIG_CONSUL_ENABLED", "true")
	t.Setenv("CONFIG_CONSUL_ADDRESS", strings.TrimPrefix(consul.URL, "http://"))
	t.Setenv("CONFIG_CONSUL_SCHEME", "http")
	t.Setenv("CONFIG_CONSUL_PATH", "config/lendora/fides-bff/config.yaml")

	got, err := loadBootstrap(loadConfigOptions{ConfigPath: configPath, EnvFilePath: filepath.Join(t.TempDir(), ".env")})
	if err != nil {
		t.Fatalf("loadBootstrap() error = %v", err)
	}
	if got.Server.HTTP.Addr != "127.0.0.1:9100" {
		t.Fatalf("Server.HTTP.Addr = %q", got.Server.HTTP.Addr)
	}
	if got.Applicant.GRPC.Timeout != "12s" {
		t.Fatalf("Applicant.GRPC.Timeout = %q", got.Applicant.GRPC.Timeout)
	}
}

func TestLoadBootstrap_ConsulEnabledMissingKeyFails(t *testing.T) {
	configPath := writeConfig(t, minimalConfig("0.0.0.0:8000"))
	consul := newConsulKVServer(t, map[string]string{})
	t.Setenv("CONFIG_CONSUL_ENABLED", "true")
	t.Setenv("CONFIG_CONSUL_ADDRESS", strings.TrimPrefix(consul.URL, "http://"))
	t.Setenv("CONFIG_CONSUL_SCHEME", "http")
	t.Setenv("CONFIG_CONSUL_PATH", "config/lendora/fides-bff/config.yaml")

	_, err := loadBootstrap(loadConfigOptions{ConfigPath: configPath, EnvFilePath: filepath.Join(t.TempDir(), ".env")})
	if err == nil {
		t.Fatal("loadBootstrap() error = nil")
	}
	if !strings.Contains(err.Error(), "consul config key not found") {
		t.Fatalf("loadBootstrap() error = %v", err)
	}
}

func TestLoadBootstrap_InvalidConsulBootstrapFails(t *testing.T) {
	configPath := writeConfig(t, minimalConfig("0.0.0.0:8000"))
	t.Setenv("CONFIG_CONSUL_ENABLED", "definitely")

	_, err := loadBootstrap(loadConfigOptions{ConfigPath: configPath, EnvFilePath: filepath.Join(t.TempDir(), ".env")})
	if err == nil {
		t.Fatal("loadBootstrap() error = nil")
	}
	if !strings.Contains(err.Error(), "CONFIG_CONSUL_ENABLED") {
		t.Fatalf("loadBootstrap() error = %v", err)
	}
}

func TestLoadBootstrap_ErrorDoesNotExposeSecrets(t *testing.T) {
	configPath := writeConfig(t, minimalConfig("0.0.0.0:8000"))
	consul := newConsulKVServer(t, map[string]string{
		"config/lendora/fides-bff/config.yaml": "server:\n  http:\n    addr: [broken\n",
	})
	t.Setenv("CONFIG_CONSUL_ENABLED", "true")
	t.Setenv("CONFIG_CONSUL_ADDRESS", strings.TrimPrefix(consul.URL, "http://"))
	t.Setenv("CONFIG_CONSUL_SCHEME", "http")
	t.Setenv("CONFIG_CONSUL_PATH", "config/lendora/fides-bff/config.yaml")
	t.Setenv("CONFIG_CONSUL_TOKEN", "super-secret-token")

	_, err := loadBootstrap(loadConfigOptions{ConfigPath: configPath, EnvFilePath: filepath.Join(t.TempDir(), ".env")})
	if err == nil {
		t.Fatal("loadBootstrap() error = nil")
	}
	for _, secret := range []string{"super-secret-token", "broken"} {
		if strings.Contains(err.Error(), secret) {
			t.Fatalf("loadBootstrap() leaked %q in error: %v", secret, err)
		}
	}
}

func TestRequiredKeySource_WatchIsDisabled(t *testing.T) {
	source := requiredKeySource{Source: envSource{}, key: "config.yaml", fullPath: "config/lendora/fides-bff/config.yaml"}
	watcher, err := source.Watch()
	if err != nil {
		t.Fatalf("Watch() error = %v", err)
	}
	if _, err := watcher.Next(); err == nil {
		t.Fatal("Watcher.Next() error = nil")
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

func newConsulKVServer(t *testing.T, values map[string]string) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/v1/kv/") {
			http.NotFound(w, r)
			return
		}
		prefix := strings.TrimPrefix(r.URL.Path, "/v1/kv/")
		prefix = strings.TrimSuffix(prefix, "/")
		var body strings.Builder
		body.WriteString("[")
		first := true
		for key, value := range values {
			if key != prefix && !strings.HasPrefix(key, prefix+"/") {
				continue
			}
			if !first {
				body.WriteString(",")
			}
			first = false
			fmt.Fprintf(&body, `{"Key":%q,"Value":%q}`, key, base64.StdEncoding.EncodeToString([]byte(value)))
		}
		body.WriteString("]")
		_, _ = w.Write([]byte(body.String()))
	}))
	t.Cleanup(server.Close)
	return server
}

var _ = conf.Bootstrap{}
