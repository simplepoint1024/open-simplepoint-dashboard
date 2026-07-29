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

func validConfig() Config {
	return Config{
		Address:           ":2892",
		SigningKey:        "test-egress-signing-key-0123456789",
		ConnectTimeout:    10 * time.Second,
		MaxTunnelDuration: time.Hour,
		TokenClockSkew:    15 * time.Second,
	}
}
