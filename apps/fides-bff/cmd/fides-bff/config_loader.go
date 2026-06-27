package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"

	consulconfig "github.com/go-kratos/kratos/contrib/config/consul/v2"
	"github.com/go-kratos/kratos/v2/config"
	"github.com/go-kratos/kratos/v2/config/file"
	"github.com/hashicorp/consul/api"
	"gopkg.in/yaml.v3"

	"github.com/spark/fides-bff/internal/conf"
)

const (
	defaultEnvFilePath    = ".env"
	defaultConsulKVConfig = "config/lendora/fides-bff/config.yaml"
)

type loadConfigOptions struct {
	ConfigPath  string
	EnvFilePath string
}

type consulBootstrap struct {
	Enabled bool
	Scheme  string
	Address string
	Path    string
	Token   string
}

func loadBootstrap(opts loadConfigOptions) (conf.Bootstrap, error) {
	if opts.ConfigPath == "" {
		opts.ConfigPath = "configs/config.yaml"
	}
	if opts.EnvFilePath == "" {
		opts.EnvFilePath = getenvDefault("CONFIG_ENV_FILE", defaultEnvFilePath)
	}
	if err := loadEnvFile(opts.EnvFilePath); err != nil {
		return conf.Bootstrap{}, err
	}

	sources := []config.Source{file.NewSource(opts.ConfigPath), newEnvSource()}
	bootstrap, err := readConsulBootstrap()
	if err != nil {
		return conf.Bootstrap{}, err
	}
	if bootstrap.Enabled {
		source, err := newConsulConfigSource(bootstrap)
		if err != nil {
			return conf.Bootstrap{}, err
		}
		sources = append(sources, source)
	}

	c := config.New(config.WithSource(sources...))
	defer func() { _ = c.Close() }()

	if err := c.Load(); err != nil {
		return conf.Bootstrap{}, sanitizeConfigError(err)
	}

	var bc conf.Bootstrap
	if err := c.Scan(&bc); err != nil {
		return conf.Bootstrap{}, sanitizeConfigError(err)
	}
	return bc, nil
}

func loadEnvFile(filename string) error {
	if filename == "" {
		return nil
	}
	file, err := os.Open(filename)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("load env file %s: %w", filepath.Base(filename), err)
	}
	defer func() { _ = file.Close() }()

	scanner := bufio.NewScanner(file)
	lineNumber := 0
	for scanner.Scan() {
		lineNumber++
		key, value, ok, err := parseEnvLine(scanner.Text())
		if err != nil {
			return fmt.Errorf("load env file %s line %d: %w", filepath.Base(filename), lineNumber, err)
		}
		if !ok {
			continue
		}
		if _, exists := os.LookupEnv(key); exists {
			continue
		}
		if err := os.Setenv(key, value); err != nil {
			return fmt.Errorf("load env file %s line %d: %w", filepath.Base(filename), lineNumber, err)
		}
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("load env file %s: %w", filepath.Base(filename), err)
	}
	return nil
}

func parseEnvLine(line string) (string, string, bool, error) {
	line = strings.TrimSpace(line)
	if line == "" || strings.HasPrefix(line, "#") {
		return "", "", false, nil
	}
	line = strings.TrimPrefix(line, "export ")
	key, value, ok := strings.Cut(line, "=")
	if !ok {
		return "", "", false, errors.New("expected KEY=value")
	}
	key = strings.TrimSpace(key)
	if key == "" || strings.ContainsAny(key, " \t") {
		return "", "", false, fmt.Errorf("invalid key %q", key)
	}
	value = strings.TrimSpace(value)
	if len(value) >= 2 {
		quote := value[0]
		if (quote == '\'' || quote == '"') && value[len(value)-1] == quote {
			value = value[1 : len(value)-1]
		}
	}
	return key, value, true, nil
}

type envSource struct{}

type noopWatcher struct{}

type envMapping struct {
	Path  []string
	Parse func(string) (any, error)
}

var envAllowlist = map[string]envMapping{
	"SERVER_HTTP_NETWORK":                       {Path: []string{"server", "http", "network"}, Parse: parseString},
	"SERVER_HTTP_ADDR":                          {Path: []string{"server", "http", "addr"}, Parse: parseString},
	"SERVER_CORS_ALLOWED_ORIGINS":               {Path: []string{"server", "cors", "allowed_origins"}, Parse: parseCSV},
	"APPLICANT_CONSUL_ADDRESS":                  {Path: []string{"applicant", "consul", "address"}, Parse: parseString},
	"APPLICANT_CONSUL_SCHEME":                   {Path: []string{"applicant", "consul", "scheme"}, Parse: parseString},
	"APPLICANT_CONSUL_SERVICE_NAME":             {Path: []string{"applicant", "consul", "service_name"}, Parse: parseString},
	"APPLICANT_GRPC_TIMEOUT":                    {Path: []string{"applicant", "grpc", "timeout"}, Parse: parseString},
	"APPLICANT_GRPC_PLAINTEXT":                  {Path: []string{"applicant", "grpc", "plaintext"}, Parse: parseBool},
	"QUOTE_CONSUL_ADDRESS":                      {Path: []string{"quote", "consul", "address"}, Parse: parseString},
	"QUOTE_CONSUL_SCHEME":                       {Path: []string{"quote", "consul", "scheme"}, Parse: parseString},
	"QUOTE_CONSUL_SERVICE_NAME":                 {Path: []string{"quote", "consul", "service_name"}, Parse: parseString},
	"QUOTE_HTTP_BASE_URL":                       {Path: []string{"quote", "http", "base_url"}, Parse: parseString},
	"QUOTE_HTTP_TIMEOUT":                        {Path: []string{"quote", "http", "timeout"}, Parse: parseString},
	"ORIGINATION_CONSUL_ADDRESS":                {Path: []string{"origination", "consul", "address"}, Parse: parseString},
	"ORIGINATION_CONSUL_SCHEME":                 {Path: []string{"origination", "consul", "scheme"}, Parse: parseString},
	"ORIGINATION_CONSUL_SERVICE_NAME":           {Path: []string{"origination", "consul", "service_name"}, Parse: parseString},
	"ORIGINATION_HTTP_BASE_URL":                 {Path: []string{"origination", "http", "base_url"}, Parse: parseString},
	"ORIGINATION_HTTP_TIMEOUT":                  {Path: []string{"origination", "http", "timeout"}, Parse: parseString},
	"AUTH_TOKEN_MODE":                           {Path: []string{"auth", "token_mode"}, Parse: parseString},
	"AUTH_TOKEN_SECRET":                         {Path: []string{"auth", "token_secret"}, Parse: parseString},
	"AUTH_ACCESS_TOKEN_TTL":                     {Path: []string{"auth", "access_token_ttl"}, Parse: parseString},
	"REGISTRY_CONSUL_ENABLED":                   {Path: []string{"registry", "consul", "enabled"}, Parse: parseBool},
	"REGISTRY_CONSUL_ADDRESS":                   {Path: []string{"registry", "consul", "address"}, Parse: parseString},
	"REGISTRY_CONSUL_SCHEME":                    {Path: []string{"registry", "consul", "scheme"}, Parse: parseString},
	"REGISTRY_CONSUL_DISCOVERY_ADDR":            {Path: []string{"registry", "consul", "discovery_addr"}, Parse: parseString},
	"REGISTRY_CONSUL_HEARTBEAT":                 {Path: []string{"registry", "consul", "heartbeat"}, Parse: parseBool},
	"REGISTRY_CONSUL_HEALTH_CHECK":              {Path: []string{"registry", "consul", "health_check"}, Parse: parseBool},
	"REGISTRY_CONSUL_HEALTH_CHECK_INTERVAL_SEC": {Path: []string{"registry", "consul", "health_check_interval_sec"}, Parse: parseInt},
	"REGISTRY_CONSUL_DEREGISTER_AFTER_SEC":      {Path: []string{"registry", "consul", "deregister_after_sec"}, Parse: parseInt},
	"REGISTRY_CONSUL_METADATA":                  {Path: []string{"registry", "consul", "metadata"}, Parse: parseStringMap},
	"OBSERVABILITY_OTEL_ENABLED":                {Path: []string{"observability", "otel", "enabled"}, Parse: parseBool},
	"OBSERVABILITY_OTEL_EXPORTER":               {Path: []string{"observability", "otel", "exporter"}, Parse: parseString},
	"OBSERVABILITY_OTEL_ENDPOINT":               {Path: []string{"observability", "otel", "endpoint"}, Parse: parseString},
	"OBSERVABILITY_OTEL_PROTOCOL":               {Path: []string{"observability", "otel", "protocol"}, Parse: parseString},
	"OBSERVABILITY_OTEL_HEADERS":                {Path: []string{"observability", "otel", "headers"}, Parse: parseStringMap},
	"OBSERVABILITY_OTEL_ENVIRONMENT":            {Path: []string{"observability", "otel", "environment"}, Parse: parseString},
	"OBSERVABILITY_OTEL_RELEASE":                {Path: []string{"observability", "otel", "release"}, Parse: parseString},
}

func newEnvSource() config.Source {
	return envSource{}
}

func (envSource) Load() ([]*config.KeyValue, error) {
	root := map[string]any{}
	for key, mapping := range envAllowlist {
		raw, ok := os.LookupEnv(key)
		if !ok {
			continue
		}
		value, err := mapping.Parse(raw)
		if err != nil {
			return nil, fmt.Errorf("environment variable %s: %w", key, err)
		}
		assignPath(root, mapping.Path, value)
	}
	data, err := json.Marshal(root)
	if err != nil {
		return nil, err
	}
	return []*config.KeyValue{{Key: "env.json", Value: data, Format: "json"}}, nil
}

func (envSource) Watch() (config.Watcher, error) {
	return noopWatcher{}, nil
}

func (noopWatcher) Next() ([]*config.KeyValue, error) {
	return nil, context.Canceled
}

func (noopWatcher) Stop() error {
	return nil
}

func assignPath(root map[string]any, path []string, value any) {
	current := root
	for i, part := range path {
		if i == len(path)-1 {
			current[part] = value
			return
		}
		next, ok := current[part].(map[string]any)
		if !ok {
			next = map[string]any{}
			current[part] = next
		}
		current = next
	}
}

func readConsulBootstrap() (consulBootstrap, error) {
	enabled, err := getenvBool("CONFIG_CONSUL_ENABLED")
	if err != nil {
		return consulBootstrap{}, err
	}
	return consulBootstrap{
		Enabled: enabled,
		Scheme:  getenvDefault("CONFIG_CONSUL_SCHEME", "http"),
		Address: os.Getenv("CONFIG_CONSUL_ADDRESS"),
		Path:    getenvDefault("CONFIG_CONSUL_PATH", defaultConsulKVConfig),
		Token:   os.Getenv("CONFIG_CONSUL_TOKEN"),
	}, nil
}

func newConsulConfigSource(bootstrap consulBootstrap) (config.Source, error) {
	if bootstrap.Address == "" {
		return nil, errors.New("consul config bootstrap address is required")
	}
	if bootstrap.Path == "" {
		return nil, errors.New("consul config bootstrap path is required")
	}

	client, err := api.NewClient(&api.Config{
		Address: bootstrap.Address,
		Scheme:  bootstrap.Scheme,
		Token:   bootstrap.Token,
	})
	if err != nil {
		return nil, fmt.Errorf("create consul config client: %w", err)
	}
	prefix := path.Dir(bootstrap.Path)
	if prefix == "." || prefix == "/" {
		return nil, errors.New("consul config path must include a directory and file name")
	}
	source, err := consulconfig.New(client, consulconfig.WithPath(prefix))
	if err != nil {
		return nil, fmt.Errorf("create consul config source for %s: %w", bootstrap.Path, err)
	}
	return requiredKeySource{Source: source, key: path.Base(bootstrap.Path), fullPath: bootstrap.Path}, nil
}

type requiredKeySource struct {
	config.Source
	key      string
	fullPath string
}

func (s requiredKeySource) Load() ([]*config.KeyValue, error) {
	kvs, err := s.Source.Load()
	if err != nil {
		return nil, fmt.Errorf("load consul config %s: %w", s.fullPath, err)
	}
	for _, kv := range kvs {
		if kv.Key == s.key {
			if err := validateRemoteConfig(kv); err != nil {
				return nil, err
			}
			return []*config.KeyValue{kv}, nil
		}
	}
	return nil, fmt.Errorf("consul config key not found: %s", s.fullPath)
}

func (s requiredKeySource) Watch() (config.Watcher, error) {
	return noopWatcher{}, nil
}

func validateRemoteConfig(kv *config.KeyValue) error {
	switch kv.Format {
	case "yaml", "yml":
		var value map[string]any
		if err := yaml.Unmarshal(kv.Value, &value); err != nil {
			return fmt.Errorf("consul config yaml is invalid")
		}
		return nil
	case "json":
		var value map[string]any
		if err := json.Unmarshal(kv.Value, &value); err != nil {
			return fmt.Errorf("consul config json is invalid")
		}
		return nil
	default:
		return fmt.Errorf("consul config format %q is unsupported", kv.Format)
	}
}

func parseString(raw string) (any, error) {
	return raw, nil
}

func parseBool(raw string) (any, error) {
	value, err := strconv.ParseBool(raw)
	if err != nil {
		return nil, err
	}
	return value, nil
}

func parseInt(raw string) (any, error) {
	value, err := strconv.Atoi(raw)
	if err != nil {
		return nil, err
	}
	return value, nil
}

func parseCSV(raw string) (any, error) {
	if raw == "" {
		return []string{}, nil
	}
	parts := strings.Split(raw, ",")
	values := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part != "" {
			values = append(values, part)
		}
	}
	return values, nil
}

func parseStringMap(raw string) (any, error) {
	values := map[string]string{}
	if strings.TrimSpace(raw) == "" {
		return values, nil
	}
	for _, part := range strings.Split(raw, ",") {
		key, value, ok := strings.Cut(part, "=")
		if !ok {
			return nil, fmt.Errorf("expected key=value pair")
		}
		key = strings.TrimSpace(key)
		if key == "" {
			return nil, fmt.Errorf("empty key")
		}
		values[key] = strings.TrimSpace(value)
	}
	return values, nil
}

func getenvDefault(key string, fallback string) string {
	if value, ok := os.LookupEnv(key); ok && value != "" {
		return value
	}
	return fallback
}

func getenvBool(key string) (bool, error) {
	value, ok := os.LookupEnv(key)
	if !ok {
		return false, nil
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return false, fmt.Errorf("environment variable %s: %w", key, err)
	}
	return parsed, nil
}

func sanitizeConfigError(err error) error {
	if err == nil {
		return nil
	}
	return fmt.Errorf("load startup config: %s", sanitizeErrorText(err.Error()))
}

func sanitizeErrorText(text string) string {
	for _, marker := range []string{"token", "secret", "authorization", "password"} {
		text = redactAfterMarker(text, marker)
		text = redactAfterMarker(text, strings.ToUpper(marker))
	}
	return text
}

func redactAfterMarker(text string, marker string) string {
	lowerText := strings.ToLower(text)
	lowerMarker := strings.ToLower(marker)
	idx := strings.Index(lowerText, lowerMarker)
	if idx < 0 {
		return text
	}
	end := idx + len(marker)
	return text[:end] + "=<redacted>"
}
