package admission

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/config"
)

var imagePattern = regexp.MustCompile(
	`^[A-Za-z0-9][A-Za-z0-9._:/-]{0,446}@sha256:[a-f0-9]{64}$`,
)

// Result is the fail-closed admission decision returned to a runtime node.
type Result struct {
	Image               string         `json:"image"`
	Admitted            bool           `json:"admitted"`
	SignatureVerified   bool           `json:"signatureVerified"`
	SBOMVerified        bool           `json:"sbomVerified"`
	VulnerabilityCounts map[string]int `json:"vulnerabilityCounts"`
	PolicyHash          string         `json:"policyHash"`
	CheckedAt           time.Time      `json:"checkedAt"`
	Reason              string         `json:"reason,omitempty"`
}

// ArtifactResult is the signature decision for a non-runnable OCI Artifact.
type ArtifactResult struct {
	Artifact          string    `json:"artifact"`
	Admitted          bool      `json:"admitted"`
	SignatureVerified bool      `json:"signatureVerified"`
	PolicyHash        string    `json:"policyHash"`
	CheckedAt         time.Time `json:"checkedAt"`
	Reason            string    `json:"reason,omitempty"`
}

// CommandRunner runs a fixed executable with an argument vector.
type CommandRunner interface {
	Run(context.Context, string, ...string) ([]byte, error)
}

type commandRunner struct{}

func (commandRunner) Run(
	ctx context.Context,
	name string,
	args ...string,
) ([]byte, error) {
	command := exec.CommandContext(ctx, name, args...)
	var standardOutput bytes.Buffer
	var standardError bytes.Buffer
	command.Stdout = &standardOutput
	command.Stderr = &standardError
	err := command.Run()
	if err != nil {
		return standardOutput.Bytes(), fmt.Errorf(
			"%s failed: %w: %s",
			filepathBase(name),
			err,
			strings.TrimSpace(standardError.String()),
		)
	}
	return standardOutput.Bytes(), nil
}

type cachedDecision struct {
	result    Result
	expiresAt time.Time
}

type cachedArtifactDecision struct {
	result    ArtifactResult
	expiresAt time.Time
}

// Verifier executes Cosign and Trivy under one immutable policy.
type Verifier struct {
	config    config.Config
	runner    CommandRunner
	semaphore chan struct{}
	mutex     sync.Mutex
	cache     map[string]cachedDecision
	artifacts map[string]cachedArtifactDecision
}

// New creates a horizontally scalable, process-local cached verifier.
func New(cfg config.Config, runner CommandRunner) *Verifier {
	if runner == nil {
		runner = commandRunner{}
	}
	return &Verifier{
		config:    cfg,
		runner:    runner,
		semaphore: make(chan struct{}, cfg.MaxConcurrentVerifications),
		cache:     make(map[string]cachedDecision),
		artifacts: make(map[string]cachedArtifactDecision),
	}
}

// VerifyArtifact validates the signature of a digest-pinned, non-runnable OCI
// Artifact. Content and media-type verification remain the caller's duty.
func (v *Verifier) VerifyArtifact(
	ctx context.Context,
	artifact string,
) ArtifactResult {
	artifact = strings.TrimSpace(artifact)
	result := ArtifactResult{
		Artifact:   artifact,
		PolicyHash: v.config.PolicyHash,
		CheckedAt:  time.Now().UTC(),
	}
	if !ValidImageReference(artifact) {
		result.Reason = "artifact reference must be pinned to a sha256 digest"
		return result
	}
	if cached, ok := v.cachedArtifact(artifact, result.CheckedAt); ok {
		return cached
	}
	select {
	case v.semaphore <- struct{}{}:
		defer func() { <-v.semaphore }()
	case <-ctx.Done():
		result.Reason = "artifact verification was canceled"
		return result
	}
	output, err := v.runner.Run(
		ctx,
		v.config.CosignBinary,
		v.cosignArguments("verify", artifact)...,
	)
	if err != nil || !validJSONDocument(output) {
		result.Reason = "artifact signature verification failed"
		v.storeArtifact(result)
		return result
	}
	result.SignatureVerified = true
	result.Admitted = true
	v.storeArtifact(result)
	return result
}

// Verify validates a digest-pinned image against signature, SBOM and CVE policy.
func (v *Verifier) Verify(ctx context.Context, image string) Result {
	image = strings.TrimSpace(image)
	result := Result{
		Image:               image,
		VulnerabilityCounts: map[string]int{},
		PolicyHash:          v.config.PolicyHash,
		CheckedAt:           time.Now().UTC(),
	}
	if !ValidImageReference(image) {
		result.Reason = "image reference must be pinned to a sha256 digest"
		return result
	}
	if cached, ok := v.cached(image, result.CheckedAt); ok {
		return cached
	}
	select {
	case v.semaphore <- struct{}{}:
		defer func() { <-v.semaphore }()
	case <-ctx.Done():
		result.Reason = "image verification was canceled"
		return result
	}

	if output, err := v.runner.Run(
		ctx,
		v.config.CosignBinary,
		v.cosignArguments("verify", image)...,
	); err != nil || !validJSONDocument(output) {
		result.Reason = "image signature verification failed"
		v.store(result)
		return result
	}
	result.SignatureVerified = true

	if v.config.RequireSBOM {
		if output, err := v.runner.Run(
			ctx,
			v.config.CosignBinary,
			v.cosignArguments("verify-attestation", image)...,
		); err != nil || !validJSONLines(output) {
			result.Reason = "signed SBOM attestation verification failed"
			v.store(result)
			return result
		}
		result.SBOMVerified = true
	} else {
		result.SBOMVerified = true
	}

	output, err := v.runner.Run(
		ctx,
		v.config.TrivyBinary,
		v.trivyArguments(image)...,
	)
	if err != nil {
		result.Reason = "vulnerability scanner failed"
		v.store(result)
		return result
	}
	counts, err := vulnerabilityCounts(output)
	if err != nil {
		result.Reason = "vulnerability scanner returned an invalid report"
		v.store(result)
		return result
	}
	result.VulnerabilityCounts = counts
	if total(counts) > 0 {
		result.Reason = "image contains vulnerabilities blocked by policy"
		v.store(result)
		return result
	}
	result.Admitted = true
	v.store(result)
	return result
}

// ValidImageReference accepts only immutable sha256 OCI references.
func ValidImageReference(image string) bool {
	return len(image) <= 512 &&
		!strings.Contains(image, "..") &&
		imagePattern.MatchString(image)
}

func (v *Verifier) cosignArguments(command string, image string) []string {
	arguments := []string{command}
	if v.config.AllowHTTPRegistry {
		arguments = append(arguments, "--allow-http-registry")
	}
	if v.config.AllowInsecureRegistry {
		arguments = append(arguments, "--allow-insecure-registry")
	}
	if command == "verify" && v.config.IgnoreTransparencyLog {
		arguments = append(arguments, "--insecure-ignore-tlog")
	}
	if command == "verify-attestation" {
		arguments = append(arguments, "--type", v.config.SBOMType)
	}
	if v.config.SignatureMode == "key" {
		arguments = append(
			arguments,
			"--key",
			v.config.CosignPublicKeyFile,
		)
	} else {
		arguments = append(
			arguments,
			"--certificate-identity-regexp",
			v.config.CertificateIdentityRegexp,
			"--certificate-oidc-issuer",
			v.config.CertificateOIDCIssuer,
		)
	}
	return append(arguments, "--output", "json", image)
}

func (v *Verifier) trivyArguments(image string) []string {
	arguments := []string{
		"image",
		"--quiet",
		"--no-progress",
		"--cache-dir",
		v.config.TrivyCacheDirectory,
		"--timeout",
		v.config.RequestTimeout.String(),
		"--scanners",
		"vuln",
		"--severity",
		strings.Join(v.config.BlockedSeverities, ","),
		"--format",
		"json",
	}
	if v.config.IgnoreUnfixed {
		arguments = append(arguments, "--ignore-unfixed")
	}
	return append(arguments, image)
}

func (v *Verifier) cached(image string, now time.Time) (Result, bool) {
	v.mutex.Lock()
	defer v.mutex.Unlock()
	decision, exists := v.cache[image]
	if !exists || !now.Before(decision.expiresAt) {
		delete(v.cache, image)
		return Result{}, false
	}
	return decision.result, true
}

func (v *Verifier) store(result Result) {
	v.mutex.Lock()
	defer v.mutex.Unlock()
	v.cache[result.Image] = cachedDecision{
		result:    result,
		expiresAt: time.Now().Add(v.config.CacheTTL),
	}
}

func (v *Verifier) cachedArtifact(
	artifact string,
	now time.Time,
) (ArtifactResult, bool) {
	v.mutex.Lock()
	defer v.mutex.Unlock()
	decision, exists := v.artifacts[artifact]
	if !exists || !now.Before(decision.expiresAt) {
		delete(v.artifacts, artifact)
		return ArtifactResult{}, false
	}
	return decision.result, true
}

func (v *Verifier) storeArtifact(result ArtifactResult) {
	v.mutex.Lock()
	defer v.mutex.Unlock()
	v.artifacts[result.Artifact] = cachedArtifactDecision{
		result:    result,
		expiresAt: time.Now().Add(v.config.CacheTTL),
	}
}

type trivyReport struct {
	Results []struct {
		Vulnerabilities []struct {
			Severity string `json:"Severity"`
		} `json:"Vulnerabilities"`
	} `json:"Results"`
}

func vulnerabilityCounts(output []byte) (map[string]int, error) {
	var report trivyReport
	decoder := json.NewDecoder(bytes.NewReader(output))
	if err := decoder.Decode(&report); err != nil {
		return nil, err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return nil, errors.New("Trivy report contains trailing content")
	}
	counts := make(map[string]int)
	for _, result := range report.Results {
		for _, vulnerability := range result.Vulnerabilities {
			severity := strings.ToUpper(strings.TrimSpace(vulnerability.Severity))
			if severity == "" {
				severity = "UNKNOWN"
			}
			counts[severity]++
		}
	}
	return counts, nil
}

func validJSONDocument(output []byte) bool {
	value := bytes.TrimSpace(output)
	if len(value) == 0 || !json.Valid(value) {
		return false
	}
	var entries []json.RawMessage
	return json.Unmarshal(value, &entries) == nil && len(entries) > 0
}

func validJSONLines(output []byte) bool {
	lines := bytes.Split(bytes.TrimSpace(output), []byte{'\n'})
	if len(lines) == 0 {
		return false
	}
	for _, line := range lines {
		if len(bytes.TrimSpace(line)) == 0 || !json.Valid(line) {
			return false
		}
	}
	return true
}

func total(values map[string]int) int {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	count := 0
	for _, key := range keys {
		count += values[key]
	}
	return count
}

func filepathBase(value string) string {
	if index := strings.LastIndexByte(value, '/'); index >= 0 {
		return value[index+1:]
	}
	return value
}
