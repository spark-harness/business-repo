package main

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/go-kratos/kratos/v2/config"
	"github.com/go-kratos/kratos/v2/config/env"
	"github.com/go-kratos/kratos/v2/config/file"

	"github.com/spark/fides-bff/internal/conf"
)

const defaultEnvFilePath = ".env"

type loadConfigOptions struct {
	ConfigPath  string
	EnvFilePath string
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

	c := config.New(
		config.WithSource(file.NewSource(opts.ConfigPath), env.NewSource()),
		config.WithResolveActualTypes(true),
	)
	defer func() { _ = c.Close() }()

	if err := c.Load(); err != nil {
		return conf.Bootstrap{}, sanitizeConfigError(err)
	}

	var bc conf.Bootstrap
	if err := c.Scan(&bc); err != nil {
		return conf.Bootstrap{}, sanitizeConfigError(err)
	}
	if err := validateBootstrap(bc); err != nil {
		return conf.Bootstrap{}, err
	}
	return bc, nil
}

func validateBootstrap(bc conf.Bootstrap) error {
	if strings.EqualFold(strings.TrimSpace(bc.Auth.TokenMode), "hmac") && strings.TrimSpace(bc.Auth.TokenSecret) == "" {
		return errors.New("AUTH_TOKEN_SECRET is required when AUTH_TOKEN_MODE=hmac")
	}
	return nil
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

func getenvDefault(key string, fallback string) string {
	if value, ok := os.LookupEnv(key); ok && value != "" {
		return value
	}
	return fallback
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
