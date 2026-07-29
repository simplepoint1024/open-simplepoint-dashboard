package egresspolicy

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"regexp"
	"sort"
	"strings"
	"time"
)

const currentVersion = 1

var hostPattern = regexp.MustCompile(
	`^(?:\*\.)?[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$`,
)

type claims struct {
	Version    int      `json:"v"`
	WorkloadID string   `json:"wid"`
	ExpiresAt  int64    `json:"exp"`
	Hosts      []string `json:"hosts"`
}

// Mint creates a stateless workload-bound Egress Proxy capability.
func Mint(
	workloadID string,
	expiresAt time.Time,
	hosts []string,
	key string,
	maximumHosts int,
) (string, []string, error) {
	normalized, err := NormalizeHosts(hosts, maximumHosts)
	if err != nil {
		return "", nil, err
	}
	if expiresAt.IsZero() || !expiresAt.After(time.Now()) {
		return "", nil, errors.New("egress policy expiry is invalid")
	}
	payload, err := json.Marshal(claims{
		Version:    currentVersion,
		WorkloadID: workloadID,
		ExpiresAt:  expiresAt.Unix(),
		Hosts:      normalized,
	})
	if err != nil {
		return "", nil, errors.New("encode egress policy")
	}
	encodedPayload := base64.RawURLEncoding.EncodeToString(payload)
	mac := hmac.New(sha256.New, []byte(key))
	_, _ = mac.Write([]byte(encodedPayload))
	signature := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return encodedPayload + "." + signature, normalized, nil
}

// NormalizeHosts validates exact and wildcard DNS allowlist entries.
func NormalizeHosts(values []string, maximumHosts int) ([]string, error) {
	if len(values) == 0 || len(values) > maximumHosts {
		return nil, errors.New("workload egress host count is invalid")
	}
	unique := make(map[string]struct{}, len(values))
	for _, value := range values {
		host := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(value), "."))
		if !validHost(host) {
			return nil, errors.New("workload egress host is invalid")
		}
		unique[host] = struct{}{}
	}
	if len(unique) != len(values) {
		return nil, errors.New("workload egress hosts must be unique")
	}
	result := make([]string, 0, len(unique))
	for host := range unique {
		result = append(result, host)
	}
	sort.Strings(result)
	return result, nil
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
