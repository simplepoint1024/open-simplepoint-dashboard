package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/admission"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/config"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/tlsidentity"
)

type imageVerifier interface {
	Verify(context.Context, string) admission.Result
	VerifyArtifact(context.Context, string) admission.ArtifactResult
}

type server struct {
	config   config.Config
	verifier imageVerifier
	logger   *slog.Logger
}

// New creates the verifier HTTP API.
func New(
	cfg config.Config,
	verifier imageVerifier,
	logger *slog.Logger,
) http.Handler {
	value := &server{config: cfg, verifier: verifier, logger: logger}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", value.health)
	mux.HandleFunc(
		"POST /internal/v1/images/verify",
		value.authenticateRuntime(value.verify),
	)
	mux.HandleFunc(
		"POST /internal/v1/artifacts/verify",
		value.authenticateAI(value.verifyArtifact),
	)
	return securityHeaders(mux)
}

func (s *server) health(response http.ResponseWriter, _ *http.Request) {
	writeJSON(response, http.StatusOK, map[string]string{"status": "UP"})
}

func (s *server) verifyArtifact(
	response http.ResponseWriter,
	request *http.Request,
) {
	var body struct {
		Artifact string `json:"artifact"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(
		response,
		request.Body,
		s.config.MaxRequestBytes,
	))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&body); err != nil {
		writeError(response, http.StatusBadRequest, "request body is invalid")
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(response, http.StatusBadRequest, "request body has trailing content")
		return
	}
	if !admission.ValidImageReference(body.Artifact) {
		writeError(
			response,
			http.StatusBadRequest,
			"artifact must be pinned to a sha256 digest",
		)
		return
	}
	result := s.verifier.VerifyArtifact(request.Context(), body.Artifact)
	s.logger.Info(
		"OCI Artifact signature admission completed",
		"caller", s.config.ExpectedAIIdentity,
		"artifact", result.Artifact,
		"admitted", result.Admitted,
		"policyHash", result.PolicyHash,
		"reason", result.Reason,
	)
	writeJSON(response, http.StatusOK, result)
}

func (s *server) verify(
	response http.ResponseWriter,
	request *http.Request,
) {
	var body struct {
		Image string `json:"image"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(
		response,
		request.Body,
		s.config.MaxRequestBytes,
	))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&body); err != nil {
		writeError(response, http.StatusBadRequest, "request body is invalid")
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(response, http.StatusBadRequest, "request body has trailing content")
		return
	}
	if !admission.ValidImageReference(body.Image) {
		writeError(
			response,
			http.StatusBadRequest,
			"image must be pinned to a sha256 digest",
		)
		return
	}
	result := s.verifier.Verify(request.Context(), body.Image)
	nodeID, _ := tlsidentity.VerifiedRuntimeNode(
		request.TLS,
		s.config.ExpectedNodeIdentityPrefix,
	)
	s.logger.Info(
		"OCI image admission completed",
		"nodeId", nodeID,
		"image", result.Image,
		"admitted", result.Admitted,
		"policyHash", result.PolicyHash,
		"reason", result.Reason,
	)
	writeJSON(response, http.StatusOK, result)
}

func (s *server) authenticateRuntime(next http.HandlerFunc) http.HandlerFunc {
	return func(response http.ResponseWriter, request *http.Request) {
		if !s.config.MTLSEnabled {
			writeError(
				response,
				http.StatusServiceUnavailable,
				"mutual TLS is required for image admission",
			)
			return
		}
		if _, ok := tlsidentity.VerifiedRuntimeNode(
			request.TLS,
			s.config.ExpectedNodeIdentityPrefix,
		); !ok {
			writeError(response, http.StatusUnauthorized, "runtime node identity is required")
			return
		}
		next(response, request)
	}
}

func (s *server) authenticateAI(next http.HandlerFunc) http.HandlerFunc {
	return func(response http.ResponseWriter, request *http.Request) {
		if !s.config.MTLSEnabled {
			writeError(
				response,
				http.StatusServiceUnavailable,
				"mutual TLS is required for artifact admission",
			)
			return
		}
		if !tlsidentity.VerifiedIdentity(
			request.TLS,
			s.config.ExpectedAIIdentity,
		) {
			writeError(
				response,
				http.StatusUnauthorized,
				"AI control-plane identity is required",
			)
			return
		}
		next(response, request)
	}
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(
		response http.ResponseWriter,
		request *http.Request,
	) {
		response.Header().Set("Cache-Control", "no-store")
		response.Header().Set("Content-Security-Policy", "default-src 'none'")
		response.Header().Set("X-Content-Type-Options", "nosniff")
		next.ServeHTTP(response, request)
	})
}

func writeJSON(response http.ResponseWriter, status int, value any) {
	response.Header().Set("Content-Type", "application/json")
	response.WriteHeader(status)
	_ = json.NewEncoder(response).Encode(value)
}

func writeError(response http.ResponseWriter, status int, message string) {
	writeJSON(response, status, map[string]string{
		"error": strings.TrimSpace(message),
	})
}
