// Package conf holds the service configuration structs.
//
// Configuration is plain Go (not protobuf): LEN-21 introduces no .proto, and the
// BFF config is small. Kratos config Scan unmarshals via JSON, so fields use
// `json` tags; the YAML config file keys map through the yaml->JSON pipeline.
package conf

// Bootstrap is the root config loaded from configs/config.yaml.
type Bootstrap struct {
	Server        Server        `json:"server"`
	Applicant     Applicant     `json:"applicant"`
	Quote         Quote         `json:"quote"`
	Origination   Origination   `json:"origination"`
	Auth          Auth          `json:"auth"`
	Registry      Registry      `json:"registry"`
	Observability Observability `json:"observability"`
}

// Server holds transport configuration.
type Server struct {
	HTTP HTTP `json:"http"`
	CORS CORS `json:"cors"`
}

// HTTP holds the REST listener configuration.
type HTTP struct {
	Network string `json:"network"`
	Addr    string `json:"addr"`
}

type CORS struct {
	AllowedOrigins []string `json:"allowed_origins"`
}

type Applicant struct {
	Consul Consul `json:"consul"`
	GRPC   GRPC   `json:"grpc"`
}

type Quote struct {
	Consul Consul `json:"consul"`
	GRPC   GRPC   `json:"grpc"`
}

type Origination struct {
	Consul Consul `json:"consul"`
	GRPC   GRPC   `json:"grpc"`
}

type Consul struct {
	Address     string `json:"address"`
	Scheme      string `json:"scheme"`
	ServiceName string `json:"service_name"`
}

type GRPC struct {
	Timeout   string `json:"timeout"`
	Plaintext bool   `json:"plaintext"`
	Target    string `json:"target"`
}

type Auth struct {
	TokenMode      string `json:"token_mode"`
	TokenSecret    string `json:"token_secret"`
	AccessTokenTTL string `json:"access_token_ttl"`
}

type Registry struct {
	Consul ServiceRegistryConsul `json:"consul"`
}

type ServiceRegistryConsul struct {
	Enabled                bool              `json:"enabled"`
	Address                string            `json:"address"`
	Scheme                 string            `json:"scheme"`
	ServiceName            string            `json:"service_name"`
	DiscoveryAddr          string            `json:"discovery_addr"`
	Heartbeat              bool              `json:"heartbeat"`
	HealthCheck            bool              `json:"health_check"`
	HealthCheckIntervalSec int               `json:"health_check_interval_sec"`
	DeregisterAfterSec     int               `json:"deregister_after_sec"`
	Metadata               map[string]string `json:"metadata"`
}

type Observability struct {
	OTel OTel `json:"otel"`
}

type OTel struct {
	SDKDisabled        bool   `json:"sdk_disabled"`
	TracesExporter     string `json:"traces_exporter"`
	TracesEndpoint     string `json:"traces_endpoint"`
	TracesProtocol     string `json:"traces_protocol"`
	TracesHeaders      string `json:"traces_headers"`
	ResourceAttributes string `json:"resource_attributes"`
	ServiceName        string `json:"service_name"`
	ServiceVersion     string `json:"service_version"`
}
