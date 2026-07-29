package proxy

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"sort"
	"strings"
	"time"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-egress-proxy/internal/config"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-egress-proxy/internal/policy"
)

var deniedPrefixes = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"),
	netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("192.0.0.0/24"),
	netip.MustParsePrefix("192.0.2.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"),
	netip.MustParsePrefix("198.51.100.0/24"),
	netip.MustParsePrefix("203.0.113.0/24"),
	netip.MustParsePrefix("240.0.0.0/4"),
	netip.MustParsePrefix("2001:db8::/32"),
}

// Resolver provides DNS lookups that can be replaced in security tests.
type Resolver interface {
	LookupNetIP(
		context.Context,
		string,
		string,
	) ([]netip.Addr, error)
}

// Server is a stateless, policy-authenticated HTTP CONNECT proxy.
type Server struct {
	config   config.Config
	resolver Resolver
	dialer   net.Dialer
	logger   *slog.Logger
}

// New creates an Egress Proxy handler.
func New(
	cfg config.Config,
	resolver Resolver,
	logger *slog.Logger,
) http.Handler {
	if resolver == nil {
		resolver = net.DefaultResolver
	}
	server := &Server{
		config:   cfg,
		resolver: resolver,
		dialer: net.Dialer{
			Timeout:   cfg.ConnectTimeout,
			KeepAlive: 30 * time.Second,
		},
		logger: logger,
	}
	return http.HandlerFunc(server.serveHTTP)
}

func (s *Server) serveHTTP(
	response http.ResponseWriter,
	request *http.Request,
) {
	if request.Method == http.MethodGet &&
		request.URL.Path == "/health" &&
		!request.URL.IsAbs() {
		securityHeaders(response.Header())
		response.Header().Set("Content-Type", "application/json")
		response.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(response, `{"status":"UP"}`)
		return
	}

	claims, err := s.authenticate(request)
	if err != nil {
		response.Header().Set(
			"Proxy-Authenticate",
			`Basic realm="open-simplepoint-egress"`,
		)
		http.Error(
			response,
			"proxy authentication required",
			http.StatusProxyAuthRequired,
		)
		return
	}
	host, port, err := destination(request)
	if err != nil {
		s.logger.Warn(
			"deny invalid egress destination",
			"workloadId", claims.WorkloadID,
			"method", request.Method,
			"error", err,
		)
		http.Error(response, "egress destination is denied", http.StatusForbidden)
		return
	}
	if !claims.AllowsHost(host) {
		s.logger.Warn(
			"deny host outside workload egress policy",
			"workloadId", claims.WorkloadID,
			"host", host,
			"method", request.Method,
		)
		http.Error(response, "egress destination is denied", http.StatusForbidden)
		return
	}
	address, err := s.publicAddress(request.Context(), host, port)
	if err != nil {
		s.logger.Warn(
			"deny unresolved or non-public egress destination",
			"workloadId", claims.WorkloadID,
			"host", host,
			"error", err,
		)
		http.Error(response, "egress destination is unavailable", http.StatusBadGateway)
		return
	}
	s.logger.Info(
		"allow workload egress",
		"workloadId", claims.WorkloadID,
		"host", host,
		"port", port,
		"method", request.Method,
	)
	if request.Method == http.MethodConnect {
		s.connect(response, request, address, claims)
		return
	}
	s.forwardHTTP(response, request, address)
}

func (s *Server) authenticate(
	request *http.Request,
) (policy.Claims, error) {
	username, token, ok := proxyBasicAuth(
		request.Header.Get("Proxy-Authorization"),
	)
	if !ok {
		return policy.Claims{}, errors.New("proxy authorization is missing")
	}
	claims, err := policy.Verify(
		token,
		s.config.SigningKey,
		time.Now(),
		s.config.TokenClockSkew,
	)
	if err != nil || claims.WorkloadID != username {
		return policy.Claims{}, errors.New("proxy authorization is invalid")
	}
	return claims, nil
}

func (s *Server) publicAddress(
	ctx context.Context,
	host string,
	port string,
) (string, error) {
	addresses, err := s.resolver.LookupNetIP(ctx, "ip", host)
	if err != nil {
		return "", fmt.Errorf("resolve destination: %w", err)
	}
	public := make([]netip.Addr, 0, len(addresses))
	for _, address := range addresses {
		value := address.Unmap()
		if isPublic(value) {
			public = append(public, value)
		}
	}
	if len(public) == 0 {
		return "", errors.New("destination has no public IP address")
	}
	sort.Slice(public, func(left, right int) bool {
		return public[left].Less(public[right])
	})
	return net.JoinHostPort(public[0].String(), port), nil
}

func (s *Server) connect(
	response http.ResponseWriter,
	request *http.Request,
	address string,
	claims policy.Claims,
) {
	upstream, err := s.dialer.DialContext(request.Context(), "tcp", address)
	if err != nil {
		http.Error(response, "egress connection failed", http.StatusBadGateway)
		return
	}
	hijacker, ok := response.(http.Hijacker)
	if !ok {
		_ = upstream.Close()
		http.Error(response, "proxy tunnel is unavailable", http.StatusInternalServerError)
		return
	}
	client, buffered, err := hijacker.Hijack()
	if err != nil {
		_ = upstream.Close()
		return
	}
	deadline := time.Now().Add(s.config.MaxTunnelDuration)
	policyDeadline := time.Unix(claims.ExpiresAt, 0)
	if policyDeadline.Before(deadline) {
		deadline = policyDeadline
	}
	_ = client.SetDeadline(deadline)
	_ = upstream.SetDeadline(deadline)
	_, err = buffered.WriteString(
		"HTTP/1.1 200 Connection Established\r\n\r\n",
	)
	if err == nil {
		err = buffered.Flush()
	}
	if err != nil {
		_ = client.Close()
		_ = upstream.Close()
		return
	}
	completed := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(upstream, buffered)
		completed <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(client, upstream)
		completed <- struct{}{}
	}()
	<-completed
	_ = client.Close()
	_ = upstream.Close()
}

func (s *Server) forwardHTTP(
	response http.ResponseWriter,
	request *http.Request,
	address string,
) {
	outbound := request.Clone(request.Context())
	outbound.RequestURI = ""
	outbound.Header = request.Header.Clone()
	removeHopHeaders(outbound.Header)
	outbound.Header.Del("Proxy-Authorization")
	transport := &http.Transport{
		Proxy:                 nil,
		DisableKeepAlives:     true,
		ForceAttemptHTTP2:     false,
		MaxIdleConns:          0,
		ResponseHeaderTimeout: s.config.ConnectTimeout,
		DialContext: func(
			ctx context.Context,
			network string,
			_ string,
		) (net.Conn, error) {
			return s.dialer.DialContext(ctx, network, address)
		},
	}
	defer transport.CloseIdleConnections()
	upstream, err := transport.RoundTrip(outbound)
	if err != nil {
		http.Error(response, "egress request failed", http.StatusBadGateway)
		return
	}
	defer upstream.Body.Close()
	removeHopHeaders(upstream.Header)
	for key, values := range upstream.Header {
		for _, value := range values {
			response.Header().Add(key, value)
		}
	}
	response.WriteHeader(upstream.StatusCode)
	_, _ = io.Copy(response, upstream.Body)
}

func destination(request *http.Request) (string, string, error) {
	if request.Method == http.MethodConnect {
		host, port, err := net.SplitHostPort(request.Host)
		if err != nil || port != "443" || !validDNSName(host) {
			return "", "", errors.New("CONNECT destination is invalid")
		}
		return strings.ToLower(strings.TrimSuffix(host, ".")), port, nil
	}
	if request.Method == http.MethodTrace ||
		request.Method == http.MethodConnect ||
		!request.URL.IsAbs() ||
		request.URL.Scheme != "http" ||
		request.URL.User != nil {
		return "", "", errors.New("HTTP proxy destination is invalid")
	}
	host := request.URL.Hostname()
	port := request.URL.Port()
	if port == "" {
		port = "80"
	}
	if port != "80" || !validDNSName(host) {
		return "", "", errors.New("HTTP proxy destination is invalid")
	}
	return strings.ToLower(strings.TrimSuffix(host, ".")), port, nil
}

func validDNSName(host string) bool {
	_, err := policy.NormalizeHosts([]string{host})
	return err == nil && !strings.HasPrefix(host, "*.")
}

func isPublic(address netip.Addr) bool {
	if !address.IsValid() ||
		!address.IsGlobalUnicast() ||
		address.IsPrivate() ||
		address.IsLoopback() ||
		address.IsLinkLocalUnicast() ||
		address.IsMulticast() ||
		address.IsUnspecified() {
		return false
	}
	for _, prefix := range deniedPrefixes {
		if prefix.Contains(address) {
			return false
		}
	}
	return true
}

func proxyBasicAuth(value string) (string, string, bool) {
	scheme, encoded, found := strings.Cut(strings.TrimSpace(value), " ")
	if !found || !strings.EqualFold(scheme, "Basic") {
		return "", "", false
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.TrimSpace(encoded))
	if err != nil {
		return "", "", false
	}
	username, password, found := strings.Cut(string(decoded), ":")
	return username, password, found && username != "" && password != ""
}

func removeHopHeaders(header http.Header) {
	for _, value := range header.Values("Connection") {
		for _, key := range strings.Split(value, ",") {
			header.Del(strings.TrimSpace(key))
		}
	}
	for _, key := range []string{
		"Connection",
		"Keep-Alive",
		"Proxy-Authenticate",
		"Proxy-Authorization",
		"Proxy-Connection",
		"Te",
		"Trailer",
		"Transfer-Encoding",
		"Upgrade",
	} {
		header.Del(key)
	}
}

func securityHeaders(header http.Header) {
	header.Set("Cache-Control", "no-store")
	header.Set("Content-Security-Policy", "default-src 'none'")
	header.Set("X-Content-Type-Options", "nosniff")
	header.Set("X-Frame-Options", "DENY")
}
