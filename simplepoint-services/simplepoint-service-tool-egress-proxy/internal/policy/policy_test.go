package policy

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"testing"
	"time"
)

const testKey = "test-egress-signing-key-0123456789"

func TestVerifyAcceptsKnownRuntimeTokenVector(t *testing.T) {
	payload := base64.RawURLEncoding.EncodeToString([]byte(
		`{"v":1,"wid":"workload-1","exp":2000000000,` +
			`"hosts":["*.example.com","api.example.net"]}`,
	))
	mac := hmac.New(sha256.New, []byte(testKey))
	_, _ = mac.Write([]byte(payload))
	token := payload + "." +
		base64.RawURLEncoding.EncodeToString(mac.Sum(nil))

	claims, err := Verify(
		token,
		testKey,
		time.Unix(1_900_000_000, 0),
		0,
	)
	if err != nil {
		t.Fatalf("verify token: %v", err)
	}
	if claims.WorkloadID != "workload-1" {
		t.Fatalf("unexpected workload: %q", claims.WorkloadID)
	}
	if !claims.AllowsHost("files.example.com") ||
		!claims.AllowsHost("api.example.net") {
		t.Fatal("expected configured hosts to be allowed")
	}
	if claims.AllowsHost("example.com") ||
		claims.AllowsHost("example.org") {
		t.Fatal("expected unconfigured hosts to be denied")
	}
}

func TestVerifyRejectsTamperingAndExpiry(t *testing.T) {
	payload := base64.RawURLEncoding.EncodeToString([]byte(
		`{"v":1,"wid":"workload-1","exp":100,"hosts":["api.example.com"]}`,
	))
	mac := hmac.New(sha256.New, []byte(testKey))
	_, _ = mac.Write([]byte(payload))
	token := payload + "." +
		base64.RawURLEncoding.EncodeToString(mac.Sum(nil))

	if _, err := Verify(token+"x", testKey, time.Unix(50, 0), 0); err == nil {
		t.Fatal("expected a tampered token to be rejected")
	}
	if _, err := Verify(token, testKey, time.Unix(101, 0), 0); err == nil {
		t.Fatal("expected an expired token to be rejected")
	}
}

func TestVerifyRejectsTrailingPayloadContent(t *testing.T) {
	payload := base64.RawURLEncoding.EncodeToString([]byte(
		`{"v":1,"wid":"workload-1","exp":2000000000,` +
			`"hosts":["api.example.com"]} {}`,
	))
	mac := hmac.New(sha256.New, []byte(testKey))
	_, _ = mac.Write([]byte(payload))
	token := payload + "." +
		base64.RawURLEncoding.EncodeToString(mac.Sum(nil))

	if _, err := Verify(
		token,
		testKey,
		time.Unix(1_900_000_000, 0),
		0,
	); err == nil {
		t.Fatal("expected trailing JSON content to be rejected")
	}
}

func TestNormalizeHostsRejectsIPsAndUnsafeWildcards(t *testing.T) {
	for _, host := range []string{
		"127.0.0.1",
		"*.com",
		"*example.com",
		"api.example.com:443",
		"api..example.com",
	} {
		if _, err := NormalizeHosts([]string{host}); err == nil {
			t.Fatalf("expected host %q to be rejected", host)
		}
	}
}
