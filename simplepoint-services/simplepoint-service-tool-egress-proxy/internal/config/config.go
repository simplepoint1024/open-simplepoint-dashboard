package config

import (
	"errors"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultAddress           = ":2892"
	defaultConnectTimeout    = 10 * time.Second
	defaultMaxTunnelDuration = 24 * time.Hour
	defaultClockSkew         = 15 * time.Second
)

// Config contains the stateless Egress Proxy security boundary.
type Config struct {
	Address           string
	SigningKey        string
	ConnectTimeout    time.Duration
	MaxTunnelDuration time.Duration
	TokenClockSkew    time.Duration
	UpstreamProxyURL  string
}

// Load reads and validates Egress Proxy environment configuration.
func Load() (Config, error) {
	cfg := Config{
		Address: strings.TrimSpace(env(
			"SIMPLEPOINT_TOOL_EGRESS_ADDRESS",
			defaultAddress,
		)),
		SigningKey: strings.TrimSpace(
			os.Getenv("SIMPLEPOINT_TOOL_EGRESS_SIGNING_KEY"),
		),
		ConnectTimeout: envDuration(
			"SIMPLEPOINT_TOOL_EGRESS_CONNECT_TIMEOUT",
			defaultConnectTimeout,
		),
		MaxTunnelDuration: envDuration(
			"SIMPLEPOINT_TOOL_EGRESS_MAX_TUNNEL_DURATION",
			defaultMaxTunnelDuration,
		),
		TokenClockSkew: envDuration(
			"SIMPLEPOINT_TOOL_EGRESS_TOKEN_CLOCK_SKEW",
			defaultClockSkew,
		),
		UpstreamProxyURL: upstreamProxyURL(),
	}
	return cfg, cfg.Validate()
}

// Validate rejects weak credentials and unsafe timeout bounds.
func (c Config) Validate() error {
	switch {
	case c.Address == "":
		return errors.New("tool egress listen address is required")
	case len(c.SigningKey) < 32:
		return errors.New(
			"SIMPLEPOINT_TOOL_EGRESS_SIGNING_KEY must contain at least 32 characters",
		)
	case c.ConnectTimeout <= 0 || c.ConnectTimeout > time.Minute:
		return errors.New("tool egress connect timeout is invalid")
	case c.MaxTunnelDuration <= 0 || c.MaxTunnelDuration > 24*time.Hour:
		return errors.New("tool egress maximum tunnel duration is invalid")
	case c.TokenClockSkew < 0 || c.TokenClockSkew > time.Minute:
		return errors.New("tool egress token clock skew is invalid")
	}
	if c.UpstreamProxyURL != "" {
		parsed, err := url.Parse(c.UpstreamProxyURL)
		if err != nil || parsed.Scheme != "http" || parsed.Hostname() == "" ||
			parsed.Port() == "" || parsed.Path != "" || parsed.RawQuery != "" ||
			parsed.Fragment != "" {
			return errors.New("tool egress upstream proxy URL is invalid")
		}
	}
	return nil
}

func upstreamProxyURL() string {
	if value := strings.TrimSpace(os.Getenv(
		"SIMPLEPOINT_TOOL_EGRESS_UPSTREAM_PROXY_URL",
	)); value != "" {
		return value
	}
	if value := strings.TrimSpace(os.Getenv("HTTPS_PROXY")); value != "" {
		return value
	}
	return strings.TrimSpace(os.Getenv("HTTP_PROXY"))
}

func env(name string, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envDuration(name string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return -1
	}
	return parsed
}

// Port returns the configured healthcheck port for command-line probes.
func (c Config) Port() int {
	value := c.Address
	if index := strings.LastIndex(value, ":"); index >= 0 {
		value = value[index+1:]
	}
	port, _ := strconv.Atoi(value)
	return port
}
