package supplychain

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/config"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/tlsidentity"
)

const maximumResponseBytes = 64 * 1024

// Result is the verifier's immutable-digest admission decision.
type Result struct {
	Image               string         `json:"image"`
	Admitted            bool           `json:"admitted"`
	SignatureVerified   bool           `json:"signatureVerified"`
	SBOMVerified        bool           `json:"sbomVerified"`
	VulnerabilityCounts map[string]int `json:"vulnerabilityCounts"`
	PolicyHash          string         `json:"policyHash"`
	CheckedAt           time.Time      `json:"checkedAt"`
	Reason              string         `json:"reason"`
}

// Client calls the independent image verifier over mutual TLS.
type Client struct {
	config     config.Config
	httpClient *http.Client
}

// New creates a fail-closed verifier client.
func New(cfg config.Config) (*Client, error) {
	tlsConfig, err := tlsidentity.ClientConfig(
		cfg.TLSCertificateFile,
		cfg.TLSPrivateKeyFile,
		cfg.TLSCAFile,
		cfg.SupplyChainServerName,
		cfg.NodeTLSIdentity(),
	)
	if err != nil {
		return nil, fmt.Errorf("configure image verifier mTLS: %w", err)
	}
	return &Client{
		config: cfg,
		httpClient: &http.Client{
			Timeout:   cfg.SupplyChainTimeout,
			Transport: &http.Transport{TLSClientConfig: tlsConfig},
			CheckRedirect: func(
				_ *http.Request,
				_ []*http.Request,
			) error {
				return http.ErrUseLastResponse
			},
		},
	}, nil
}

// Verify requires a positive, identity-bound decision for the requested image.
func (c *Client) Verify(ctx context.Context, image string) (Result, error) {
	payload, err := json.Marshal(map[string]string{"image": image})
	if err != nil {
		return Result{}, fmt.Errorf("encode image verification request: %w", err)
	}
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		c.config.SupplyChainVerifierURL+"/internal/v1/images/verify",
		bytes.NewReader(payload),
	)
	if err != nil {
		return Result{}, fmt.Errorf("create image verification request: %w", err)
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := c.httpClient.Do(request)
	if err != nil {
		return Result{}, fmt.Errorf("call image verifier: %w", err)
	}
	defer response.Body.Close()
	if response.TLS == nil || !tlsidentity.VerifiedPeerURI(
		response.TLS,
		c.config.SupplyChainIdentity,
	) {
		return Result{}, errors.New("image verifier TLS identity is invalid")
	}
	if response.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, maximumResponseBytes))
		return Result{}, fmt.Errorf(
			"image verifier returned HTTP %d",
			response.StatusCode,
		)
	}
	decoder := json.NewDecoder(io.LimitReader(
		response.Body,
		maximumResponseBytes,
	))
	decoder.DisallowUnknownFields()
	var result Result
	if err = decoder.Decode(&result); err != nil {
		return Result{}, fmt.Errorf("decode image verification response: %w", err)
	}
	if err = decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return Result{}, errors.New("image verifier response has trailing content")
	}
	if result.Image != image ||
		!strings.HasPrefix(result.PolicyHash, "sha256:") ||
		len(result.PolicyHash) != len("sha256:")+64 ||
		result.CheckedAt.IsZero() {
		return Result{}, errors.New("image verifier response is inconsistent")
	}
	return result, nil
}
