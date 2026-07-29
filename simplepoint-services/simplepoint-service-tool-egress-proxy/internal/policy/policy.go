package policy

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"regexp"
	"sort"
	"strings"
	"time"
)

const (
	currentVersion = 1
	maximumHosts   = 32
)

var (
	workloadPattern = regexp.MustCompile(
		`^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$`,
	)
	hostPattern = regexp.MustCompile(
		`^(?:\*\.)?[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$`,
	)
)

// Claims is the signed, short-lived egress capability for one workload.
type Claims struct {
	Version    int      `json:"v"`
	WorkloadID string   `json:"wid"`
	ExpiresAt  int64    `json:"exp"`
	Hosts      []string `json:"hosts"`
}

// Verify authenticates and normalizes a compact HMAC policy token.
func Verify(
	token string,
	key string,
	now time.Time,
	clockSkew time.Duration,
) (Claims, error) {
	encodedPayload, encodedSignature, found := strings.Cut(token, ".")
	if !found || encodedPayload == "" || encodedSignature == "" ||
		strings.Contains(encodedSignature, ".") {
		return Claims{}, errors.New("egress policy token is malformed")
	}
	signature, err := base64.RawURLEncoding.DecodeString(encodedSignature)
	if err != nil {
		return Claims{}, errors.New("egress policy signature is malformed")
	}
	expected := sign(encodedPayload, key)
	if !hmac.Equal(signature, expected) {
		return Claims{}, errors.New("egress policy signature is invalid")
	}
	payload, err := base64.RawURLEncoding.DecodeString(encodedPayload)
	if err != nil {
		return Claims{}, errors.New("egress policy payload is malformed")
	}
	var claims Claims
	decoder := json.NewDecoder(strings.NewReader(string(payload)))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&claims); err != nil {
		return Claims{}, errors.New("egress policy payload is invalid")
	}
	if err = decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return Claims{}, errors.New("egress policy payload has trailing content")
	}
	if claims.Version != currentVersion ||
		!workloadPattern.MatchString(claims.WorkloadID) ||
		claims.ExpiresAt <= 0 ||
		now.After(time.Unix(claims.ExpiresAt, 0).Add(clockSkew)) {
		return Claims{}, errors.New("egress policy claims are invalid or expired")
	}
	claims.Hosts, err = NormalizeHosts(claims.Hosts)
	if err != nil {
		return Claims{}, err
	}
	return claims, nil
}

// NormalizeHosts canonicalizes exact and wildcard DNS names.
func NormalizeHosts(values []string) ([]string, error) {
	if len(values) == 0 || len(values) > maximumHosts {
		return nil, errors.New("egress policy host count is invalid")
	}
	unique := make(map[string]struct{}, len(values))
	for _, value := range values {
		host := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(value), "."))
		if !validHost(host) {
			return nil, errors.New("egress policy host is invalid")
		}
		unique[host] = struct{}{}
	}
	if len(unique) != len(values) {
		return nil, errors.New("egress policy hosts must be unique")
	}
	result := make([]string, 0, len(unique))
	for host := range unique {
		result = append(result, host)
	}
	sort.Strings(result)
	return result, nil
}

// AllowsHost checks exact hosts and one-or-more-label wildcard suffixes.
func (c Claims) AllowsHost(value string) bool {
	host := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(value), "."))
	for _, allowed := range c.Hosts {
		if host == allowed {
			return true
		}
		if strings.HasPrefix(allowed, "*.") {
			suffix := allowed[1:]
			if len(host) > len(suffix) && strings.HasSuffix(host, suffix) {
				return true
			}
		}
	}
	return false
}

func validHost(host string) bool {
	if host == "" ||
		len(host) > 253 ||
		strings.Contains(host, "..") ||
		strings.ContainsAny(host, ":/[]@%") ||
		!hostPattern.MatchString(host) {
		return false
	}
	candidate := strings.TrimPrefix(host, "*.")
	if net.ParseIP(candidate) != nil {
		return false
	}
	labels := strings.Split(candidate, ".")
	if len(labels) < 2 {
		return false
	}
	for _, label := range labels {
		if label == "" ||
			len(label) > 63 ||
			strings.HasPrefix(label, "-") ||
			strings.HasSuffix(label, "-") {
			return false
		}
	}
	return true
}

func sign(encodedPayload string, key string) []byte {
	mac := hmac.New(sha256.New, []byte(key))
	_, _ = mac.Write([]byte(encodedPayload))
	return mac.Sum(nil)
}
