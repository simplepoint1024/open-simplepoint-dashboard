package egresspolicy

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestMintCreatesCanonicalWorkloadBoundClaims(t *testing.T) {
	token, hosts, err := Mint(
		"workload-1",
		time.Unix(2_000_000_000, 0),
		[]string{"API.EXAMPLE.NET.", "*.example.com"},
		"test-egress-signing-key-0123456789",
		32,
	)
	if err != nil {
		t.Fatalf("mint token: %v", err)
	}
	if hosts[0] != "*.example.com" || hosts[1] != "api.example.net" {
		t.Fatalf("unexpected normalized hosts: %v", hosts)
	}
	payload, _, found := strings.Cut(token, ".")
	if !found {
		t.Fatal("token has no signature separator")
	}
	decoded, err := base64.RawURLEncoding.DecodeString(payload)
	if err != nil {
		t.Fatalf("decode token: %v", err)
	}
	var claims claims
	if err = json.Unmarshal(decoded, &claims); err != nil {
		t.Fatalf("decode claims: %v", err)
	}
	if claims.WorkloadID != "workload-1" ||
		claims.ExpiresAt != 2_000_000_000 {
		t.Fatalf("unexpected claims: %+v", claims)
	}
}

func TestNormalizeHostsRejectsIPsDuplicatesAndBroadWildcards(t *testing.T) {
	cases := [][]string{
		{"127.0.0.1"},
		{"*.com"},
		{"api.example.com", "API.EXAMPLE.COM"},
	}
	for _, values := range cases {
		if _, err := NormalizeHosts(values, 32); err == nil {
			t.Fatalf("expected hosts to be rejected: %v", values)
		}
	}
}
