package proxy

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
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

func TestOpenTunnelChainsThroughAuthenticatedUpstreamProxy(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	requestChannel := make(chan *http.Request, 1)
	go func() {
		connection, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer connection.Close()
		request, readErr := http.ReadRequest(bufio.NewReader(connection))
		if readErr != nil {
			return
		}
		requestChannel <- request
		_, _ = io.WriteString(
			connection,
			"HTTP/1.1 200 Connection Established\r\n\r\n",
		)
	}()
	upstream, err := url.Parse(
		"http://proxy-user:proxy-password@" + listener.Addr().String(),
	)
	if err != nil {
		t.Fatal(err)
	}
	server := &Server{
		dialer:   net.Dialer{Timeout: time.Second},
		upstream: upstream,
	}
	connection, _, err := server.openTunnel(
		context.Background(),
		"api.example.com:443",
		"93.184.216.34:443",
	)
	if err != nil {
		t.Fatalf("open upstream tunnel: %v", err)
	}
	_ = connection.Close()
	request := <-requestChannel
	if request.Method != http.MethodConnect ||
		request.Host != "api.example.com:443" {
		t.Fatalf("unexpected CONNECT request: %s %s", request.Method, request.Host)
	}
	if request.Header.Get("Proxy-Authorization") !=
		basic("proxy-user", "proxy-password") {
		t.Fatal("expected upstream proxy authorization")
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
