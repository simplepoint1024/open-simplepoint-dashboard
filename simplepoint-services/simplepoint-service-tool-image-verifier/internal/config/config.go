package config

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	defaultAddress        = ":2893"
	defaultRequestTimeout = 10 * time.Minute
	defaultCacheTTL       = 10 * time.Minute
)

var severityValues = map[string]struct{}{
	"UNKNOWN":  {},
	"LOW":      {},
	"MEDIUM":   {},
	"HIGH":     {},
	"CRITICAL": {},
}

// Config contains the fail-closed OCI admission policy.
type Config struct {
	Address                    string
	RequestTimeout             time.Duration
	ShutdownTimeout            time.Duration
	MaxRequestBytes            int64
	MaxConcurrentVerifications int
	CacheTTL                   time.Duration
	MTLSEnabled                bool
	TLSCertificateFile         string
	TLSPrivateKeyFile          string
	TLSCAFile                  string
	TLSIdentity                string
	ExpectedNodeIdentityPrefix string
	ExpectedAIIdentity         string
	SignatureMode              string
	CosignPublicKeyFile        string
	CertificateIdentityRegexp  string
	CertificateOIDCIssuer      string
	RequireSBOM                bool
	SBOMType                   string
	BlockedSeverities          []string
	IgnoreUnfixed              bool
	CosignBinary               string
	AllowHTTPRegistry          bool
	AllowInsecureRegistry      bool
	IgnoreTransparencyLog      bool
	TrivyBinary                string
	TrivyCacheDirectory        string
	PolicyHash                 string
}

// Load reads and validates verifier environment configuration.
func Load() (Config, error) {
	cfg := Config{
		Address:                    env("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_ADDRESS", defaultAddress),
		RequestTimeout:             envDuration("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_REQUEST_TIMEOUT", defaultRequestTimeout),
		ShutdownTimeout:            envDuration("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_SHUTDOWN_TIMEOUT", 10*time.Second),
		MaxRequestBytes:            envInt64("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_MAX_REQUEST_BYTES", 16*1024),
		MaxConcurrentVerifications: int(envInt64("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_MAX_CONCURRENT", 2)),
		CacheTTL:                   envDuration("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_CACHE_TTL", defaultCacheTTL),
		MTLSEnabled:                envBool("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_MTLS_ENABLED", true),
		TLSCertificateFile:         strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_TLS_CERT_FILE")),
		TLSPrivateKeyFile:          strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_TLS_KEY_FILE")),
		TLSCAFile:                  strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_TLS_CA_FILE")),
		TLSIdentity:                env("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_TLS_IDENTITY", "spiffe://open-simplepoint/tool-image-verifier"),
		ExpectedNodeIdentityPrefix: env("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_NODE_IDENTITY_PREFIX", "spiffe://open-simplepoint/runtime-node/"),
		ExpectedAIIdentity:         env("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_AI_IDENTITY", "spiffe://open-simplepoint/ai-control-plane"),
		SignatureMode:              strings.ToLower(env("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_SIGNATURE_MODE", "keyless")),
		CosignPublicKeyFile:        strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_COSIGN_PUBLIC_KEY_FILE")),
		CertificateIdentityRegexp:  strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_CERTIFICATE_IDENTITY_REGEXP")),
		CertificateOIDCIssuer:      strings.TrimSpace(os.Getenv("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_CERTIFICATE_OIDC_ISSUER")),
		RequireSBOM:                envBool("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_REQUIRE_SBOM", true),
		SBOMType:                   strings.ToLower(env("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_SBOM_TYPE", "spdx")),
		BlockedSeverities:          csv(env("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_BLOCKED_SEVERITIES", "HIGH,CRITICAL")),
		IgnoreUnfixed:              envBool("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_IGNORE_UNFIXED", false),
		CosignBinary:               env("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_COSIGN_BINARY", "/usr/local/bin/cosign"),
		AllowHTTPRegistry:          envBool("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_ALLOW_HTTP_REGISTRY", false),
		AllowInsecureRegistry:      envBool("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_ALLOW_INSECURE_REGISTRY", false),
		IgnoreTransparencyLog:      envBool("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_IGNORE_TRANSPARENCY_LOG", false),
		TrivyBinary:                env("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_TRIVY_BINARY", "/usr/local/bin/trivy"),
		TrivyCacheDirectory:        env("SIMPLEPOINT_TOOL_IMAGE_VERIFIER_TRIVY_CACHE_DIR", "/var/cache/trivy"),
	}
	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	hash, err := cfg.policyHash()
	if err != nil {
		return Config{}, err
	}
	cfg.PolicyHash = hash
	return cfg, nil
}

// Validate rejects incomplete identity or admission policy.
func (c Config) Validate() error {
	switch {
	case c.RequestTimeout < time.Second || c.RequestTimeout > 30*time.Minute:
		return errors.New("image verifier request timeout is invalid")
	case c.ShutdownTimeout <= 0 || c.ShutdownTimeout > time.Minute:
		return errors.New("image verifier shutdown timeout is invalid")
	case c.MaxRequestBytes < 1024 || c.MaxRequestBytes > 1024*1024:
		return errors.New("image verifier maximum request size is invalid")
	case c.MaxConcurrentVerifications <= 0 || c.MaxConcurrentVerifications > 32:
		return errors.New("image verifier concurrency is invalid")
	case c.CacheTTL < time.Minute || c.CacheTTL > 24*time.Hour:
		return errors.New("image verifier cache TTL is invalid")
	case c.MTLSEnabled && (!safePath(c.TLSCertificateFile) ||
		!safePath(c.TLSPrivateKeyFile) ||
		!safePath(c.TLSCAFile)):
		return errors.New("image verifier TLS files are required")
	case c.MTLSEnabled && (!validSPIFFE(c.TLSIdentity) ||
		!validSPIFFEPrefix(c.ExpectedNodeIdentityPrefix) ||
		!validSPIFFE(c.ExpectedAIIdentity)):
		return errors.New("image verifier TLS identities are invalid")
	case c.SignatureMode != "key" && c.SignatureMode != "keyless":
		return errors.New("image verifier signature mode must be key or keyless")
	case c.SignatureMode == "key" && !safePath(c.CosignPublicKeyFile):
		return errors.New("Cosign public key file is required in key mode")
	case c.SignatureMode == "keyless" &&
		(c.CertificateIdentityRegexp == "" ||
			len(c.CertificateIdentityRegexp) > 512 ||
			!validRegexp(c.CertificateIdentityRegexp) ||
			!validHTTPSURL(c.CertificateOIDCIssuer)):
		return errors.New("Cosign keyless identity and OIDC issuer are invalid")
	case c.SBOMType != "spdx" && c.SBOMType != "cyclonedx":
		return errors.New("image verifier SBOM type is invalid")
	case len(c.BlockedSeverities) == 0 || len(c.BlockedSeverities) > len(severityValues):
		return errors.New("image verifier blocked severities are invalid")
	case !safePath(c.CosignBinary) || !safePath(c.TrivyBinary) ||
		!safePath(c.TrivyCacheDirectory):
		return errors.New("image verifier executable or cache path is invalid")
	}
	seen := make(map[string]struct{}, len(c.BlockedSeverities))
	for _, severity := range c.BlockedSeverities {
		if _, valid := severityValues[severity]; !valid {
			return errors.New("image verifier blocked severity is invalid")
		}
		if _, duplicate := seen[severity]; duplicate {
			return errors.New("image verifier blocked severities must be unique")
		}
		seen[severity] = struct{}{}
	}
	return nil
}

func (c Config) policyHash() (string, error) {
	keyDigest := ""
	if c.SignatureMode == "key" {
		value, err := os.ReadFile(c.CosignPublicKeyFile)
		if err != nil {
			return "", fmt.Errorf("read Cosign public key: %w", err)
		}
		digest := sha256.Sum256(value)
		keyDigest = hex.EncodeToString(digest[:])
	}
	severities := append([]string(nil), c.BlockedSeverities...)
	sort.Strings(severities)
	policy := strings.Join([]string{
		c.SignatureMode,
		keyDigest,
		c.CertificateIdentityRegexp,
		c.CertificateOIDCIssuer,
		strconv.FormatBool(c.RequireSBOM),
		c.SBOMType,
		strings.Join(severities, ","),
		strconv.FormatBool(c.IgnoreUnfixed),
		strconv.FormatBool(c.AllowHTTPRegistry),
		strconv.FormatBool(c.AllowInsecureRegistry),
		strconv.FormatBool(c.IgnoreTransparencyLog),
	}, "\n")
	digest := sha256.Sum256([]byte(policy))
	return "sha256:" + hex.EncodeToString(digest[:]), nil
}

func safePath(value string) bool {
	cleaned := filepath.Clean(value)
	return filepath.IsAbs(cleaned) && cleaned == value && cleaned != "/" &&
		!strings.ContainsRune(cleaned, '\x00')
}

func validRegexp(value string) bool {
	_, err := regexp.Compile(value)
	return err == nil
}

func validHTTPSURL(value string) bool {
	parsed, err := url.Parse(value)
	return err == nil && parsed.Scheme == "https" && parsed.Host != "" &&
		parsed.User == nil && parsed.Fragment == ""
}

func validSPIFFE(value string) bool {
	parsed, err := url.Parse(value)
	return err == nil && parsed.Scheme == "spiffe" && parsed.Host != "" &&
		parsed.User == nil && parsed.RawQuery == "" && parsed.Fragment == ""
}

func validSPIFFEPrefix(value string) bool {
	return strings.HasSuffix(value, "/") && validSPIFFE(strings.TrimSuffix(value, "/"))
}

func env(name string, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envBool(name string, fallback bool) bool {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func envInt64(name string, fallback int64) int64 {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return fallback
	}
	return parsed
}

func envDuration(name string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func csv(value string) []string {
	result := make([]string, 0)
	for item := range strings.SplitSeq(value, ",") {
		if normalized := strings.ToUpper(strings.TrimSpace(item)); normalized != "" {
			result = append(result, normalized)
		}
	}
	return result
}
