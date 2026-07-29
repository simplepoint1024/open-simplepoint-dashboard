package proxy

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"testing"
	"time"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-egress-proxy/internal/config"
)

const testSigningKey = "test-egress-signing-key-0123456789"

type staticResolver struct {
	addresses []netip.Addr
}

func (r staticResolver) LookupNetIP(
	_ context.Context,
	_ string,
	_ string,
) ([]netip.Addr, error) {
	return r.addresses, nil
}

func TestProxyRequiresSignedWorkloadPolicy(t *testing.T) {
	handler := New(
		testConfig(),
		staticResolver{addresses: []netip.Addr{
			netip.MustParseAddr("93.184.216.34"),
		}},
		discardLogger(),
	)
	request := httptest.NewRequest(
		http.MethodGet,
		"http://api.example.com/test",
		nil,
	)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusProxyAuthRequired {
		t.Fatalf("expected 407, got %d", response.Code)
	}
}

func TestProxyDeniesHostOutsideSignedPolicy(t *testing.T) {
	handler := New(
		testConfig(),
		staticResolver{addresses: []netip.Addr{
			netip.MustParseAddr("93.184.216.34"),
		}},
		discardLogger(),
	)
	request := httptest.NewRequest(
		http.MethodConnect,
		"http://denied.example.net:443",
		nil,
	)
	request.Host = "denied.example.net:443"
	request.Header.Set(
		"Proxy-Authorization",
		basic("workload-1", token("api.example.com")),
	)
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", response.Code)
	}
}

func TestPublicAddressRejectsPrivateAndReservedRanges(t *testing.T) {
	for _, value := range []string{
		"127.0.0.1",
		"10.0.0.1",
		"100.64.0.1",
		"169.254.169.254",
		"192.0.2.1",
		"::1",
		"fc00::1",
		"2001:db8::1",
	} {
		address, ok := netipFromString(value)
		if !ok || isPublic(address) {
			t.Fatalf("expected %s to be denied", value)
		}
	}
	if address, ok := netipFromString("93.184.216.34"); !ok || !isPublic(address) {
		t.Fatal("expected a public address to be allowed")
	}
}

func testConfig() config.Config {
	return config.Config{
		Address:           ":2892",
		SigningKey:        testSigningKey,
		ConnectTimeout:    time.Second,
		MaxTunnelDuration: time.Hour,
		TokenClockSkew:    15 * time.Second,
	}
}

func token(host string) string {
	payload := base64.RawURLEncoding.EncodeToString([]byte(
		`{"v":1,"wid":"workload-1","exp":4102444800,"hosts":["` +
			host + `"]}`,
	))
	mac := hmac.New(sha256.New, []byte(testSigningKey))
	_, _ = mac.Write([]byte(payload))
	return payload + "." +
		base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func basic(username string, password string) string {
	return "Basic " + base64.StdEncoding.EncodeToString(
		[]byte(username+":"+password),
	)
}

func discardLogger() *slog.Logger {
	return slog.New(slog.NewJSONHandler(io.Discard, nil))
}

func netipFromString(value string) (netip.Addr, bool) {
	address, err := netip.ParseAddr(value)
	return address, err == nil
}
