// Package conf holds the service configuration structs.
//
// Configuration is plain Go (not protobuf): LEN-21 introduces no .proto, and the
// BFF config is small. Kratos config Scan unmarshals via JSON, so fields use
// `json` tags; the YAML config file keys map through the yaml->JSON pipeline.
package conf

// Bootstrap is the root config loaded from configs/config.yaml.
type Bootstrap struct {
	Server Server `json:"server"`
}

// Server holds transport configuration.
type Server struct {
	HTTP HTTP `json:"http"`
}

// HTTP holds the REST listener configuration.
type HTTP struct {
	Network string `json:"network"`
	Addr    string `json:"addr"`
}
