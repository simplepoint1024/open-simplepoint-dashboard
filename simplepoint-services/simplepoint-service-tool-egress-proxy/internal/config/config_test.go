package config

import (
	"testing"
	"time"
)

func TestValidateRequiresStrongSigningKey(t *testing.T) {
	cfg := validConfig()
	cfg.SigningKey = "short"
	if err := cfg.Validate(); err == nil {
		t.Fatal("expected a short signing key to be rejected")
	}
}

func TestValidateAcceptsBoundedConfiguration(t *testing.T) {
	if err := validConfig().Validate(); err != nil {
		t.Fatalf("expected valid configuration: %v", err)
	}
}

func TestValidateAcceptsHttpUpstreamProxy(t *testing.T) {
	cfg := validConfig()
	cfg.UpstreamProxyURL = "http://proxy-user:proxy-password@proxy.example:3128"
	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected valid upstream proxy: %v", err)
	}
}

func TestValidateRejectsUnsafeUpstreamProxy(t *testing.T) {
	for _, value := range []string{
		"https://proxy.example:3128",
		"http://proxy.example",
		"http://proxy.example:3128/path",
	} {
		cfg := validConfig()
		cfg.UpstreamProxyURL = value
		if err := cfg.Validate(); err == nil {
			t.Fatalf("expected upstream proxy %q to be rejected", value)
		}
	}
}

func validConfig() Config {
	return Config{
		Address:           ":2892",
		SigningKey:        "test-egress-signing-key-0123456789",
		ConnectTimeout:    10 * time.Second,
		MaxTunnelDuration: time.Hour,
		TokenClockSkew:    15 * time.Second,
	}
}
