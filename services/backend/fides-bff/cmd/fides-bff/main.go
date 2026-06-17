// Command fides-bff is the Lendora front-end BFF (LEN-21).
//
// It exposes REST /api/v1 to the fides front end. T1 wires only a runnable
// skeleton with a health endpoint; downstream gRPC clients and cross-cutting
// middleware arrive in later tasks.
package main

import (
	"flag"
	"os"
	"time"

	"github.com/go-kratos/kratos/v2"
	"github.com/go-kratos/kratos/v2/config"
	"github.com/go-kratos/kratos/v2/config/file"
	"github.com/go-kratos/kratos/v2/log"
	"github.com/go-kratos/kratos/v2/transport/http"

	"github.com/spark/fides-bff/internal/biz"
	"github.com/spark/fides-bff/internal/conf"
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

func newApp(logger log.Logger, hs *http.Server) *kratos.App {
	return kratos.New(
		kratos.Name(Name),
		kratos.Version(Version),
		kratos.Logger(logger),
		kratos.Server(hs),
		// Bound graceful shutdown so an in-flight request cannot block stop
		// indefinitely once downstream calls/middleware land in later tasks.
		kratos.StopTimeout(10*time.Second),
	)
}

func main() {
	flag.Parse()

	logger := log.With(log.NewStdLogger(os.Stdout),
		"service.name", Name,
		"service.version", Version,
	)

	c := config.New(config.WithSource(file.NewSource(flagconf)))
	defer func() { _ = c.Close() }()

	if err := c.Load(); err != nil {
		panic(err)
	}

	var bc conf.Bootstrap
	if err := c.Scan(&bc); err != nil {
		panic(err)
	}

	app, cleanup, err := wireApp(&bc.Server, biz.Version(Version), logger)
	if err != nil {
		panic(err)
	}
	defer cleanup()

	if err := app.Run(); err != nil {
		panic(err)
	}
}
