# fides-bff — Lendora front-end BFF (LEN-21)
#
# Common local workflow:
#   make run      # one-command local start (uses configs/config.yaml)
#   make test     # unit tests
#   make lint     # golangci-lint
#   make generate # regenerate wire_gen.go after DI changes

APP        := fides-bff
MAIN       := ./cmd/fides-bff
BIN_DIR    := bin
VERSION    ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
LDFLAGS    := -X main.Version=$(VERSION)

.PHONY: all
all: lint test build

## init: install developer tooling (wire, golangci-lint)
.PHONY: init
init:
	go install github.com/google/wire/cmd/wire@latest
	go install github.com/golangci/golangci-lint/cmd/golangci-lint@latest

## generate: regenerate wire dependency injection
.PHONY: generate
generate:
	cd cmd/$(APP) && wire

## tidy: sync go.mod/go.sum
.PHONY: tidy
tidy:
	go mod tidy

## build: compile the service binary with version stamped in
.PHONY: build
build:
	mkdir -p $(BIN_DIR)
	go build -ldflags "$(LDFLAGS)" -o $(BIN_DIR)/$(APP) $(MAIN)

## run: start the service locally
.PHONY: run
run:
	go run -ldflags "$(LDFLAGS)" $(MAIN) -conf configs/config.yaml

## test: run unit tests
.PHONY: test
test:
	go test ./... -count=1

## vet: run go vet
.PHONY: vet
vet:
	go vet ./...

## lint: run golangci-lint
.PHONY: lint
lint:
	golangci-lint run ./...

## clean: remove build artifacts
.PHONY: clean
clean:
	rm -rf $(BIN_DIR)
