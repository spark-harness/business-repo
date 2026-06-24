# syntax=docker/dockerfile:1.7
FROM golang:1.26-alpine AS build
WORKDIR /workspace
RUN apk add --no-cache git
COPY packages/bffkit/go.mod packages/bffkit/go.sum packages/bffkit/
COPY services/backend/fides-bff/go.mod services/backend/fides-bff/go.sum services/backend/fides-bff/
WORKDIR /workspace/services/backend/fides-bff
RUN --mount=type=secret,id=github_token \
    git config --global url."https://x-access-token:$(cat /run/secrets/github_token)@github.com/".insteadOf "https://github.com/" \
    && GOPRIVATE=github.com/spark-harness/* go mod download
WORKDIR /workspace
COPY packages/bffkit packages/bffkit
COPY services/backend/fides-bff services/backend/fides-bff
WORKDIR /workspace/services/backend/fides-bff
ARG VERSION=dev
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags "-s -w -X main.Version=${VERSION}" -o /out/fides-bff ./cmd/fides-bff

FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /app
COPY --from=build /out/fides-bff /app/fides-bff
COPY services/backend/fides-bff/configs/config.yaml /app/configs/config.yaml
EXPOSE 8000
ENTRYPOINT ["/app/fides-bff", "-conf", "/app/configs/config.yaml"]
