// Command fides-bff is the Lendora front-end BFF (LEN-21).
//
// It exposes REST /api/v1 to the fides front end. T1 wires only a runnable
// skeleton with a health endpoint; downstream gRPC clients and cross-cutting
// middleware arrive in later tasks.
package main

import (
	"context"
	"flag"
	"io"
	"log/slog"
	"os"
	"time"

	"github.com/go-kratos/kratos/v3"
	"github.com/go-kratos/kratos/v3/log"
	"github.com/go-kratos/kratos/v3/transport/http"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/observability"
)

// Name is the service name registered with Kratos.
const Name = "fides-bff"

// Version is the build version, overridden at build time via
// -ldflags "-X main.Version=...". It defaults to "dev" for local runs.
var Version = "dev"

var flagconf string

func init() {
	flag.StringVar(&flagconf, "conf", "configs/config.yaml", "config path, eg: -conf config.yaml")
}

func newLogger(writer io.Writer) *slog.Logger {
	return log.NewLogger(
		log.NewHandler(log.WithWriter(writer), log.WithFormat(log.FormatJSON), log.WithReplaceAttr(replaceLogAttr)),
	).With(
		"service.name", Name,
		"service.version", Version,
	)
}

func replaceLogAttr(_ []string, attr slog.Attr) slog.Attr {
	switch attr.Key {
	case slog.TimeKey:
		attr.Key = "timestamp"
	case slog.MessageKey:
		attr.Key = "message"
	}
	return attr
}

func newApp(logger *slog.Logger, hs *http.Server, registration *registration) *kratos.App {
	name := Name
	if registration != nil && registration.name != "" {
		name = registration.name
	}
	opts := []kratos.Option{
		kratos.Name(name),
		kratos.Version(Version),
		kratos.Logger(logger),
		kratos.Server(hs),
		kratos.StopTimeout(10 * time.Second),
	}
	if registration != nil && registration.registrar != nil {
		opts = append(opts, kratos.Registrar(registration.registrar), kratos.Metadata(registration.metadata))
	}
	if registration != nil && registration.endpoint != nil {
		opts = append(opts, kratos.Endpoint(registration.endpoint))
	}
	return kratos.New(opts...)
}

func main() {
	flag.Parse()

	logger := newLogger(os.Stdout)

	bc, err := loadBootstrap(loadConfigOptions{ConfigPath: flagconf})
	if err != nil {
		panic(err)
	}

	otelShutdown, err := observability.Setup(context.Background(), bc.Observability.OTel, Name, Version)
	if err != nil {
		panic(err)
	}
	defer func() { _ = otelShutdown(context.Background()) }()

	app, cleanup, err := wireApp(&bc.Server, &bc.Applicant, &bc.Quote, &bc.Origination, &bc.Auth, &bc.Registry, bc.Observability, biz.Version(Version), logger)
	if err != nil {
		panic(err)
	}
	defer cleanup()

	if err := app.Run(); err != nil {
		panic(err)
	}
}
