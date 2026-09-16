package httpapi

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/config"
	runtimeapi "github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/runtime"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/tlsidentity"
)

// Runtime defines the engine operations exposed to the trusted scheduler.
type Runtime interface {
	NodeStatus(context.Context) (runtimeapi.NodeStatus, error)
	Prepare(context.Context, string) (runtimeapi.ImageStatus, error)
	ProbeMCP(
		context.Context,
		runtimeapi.StartRequest,
	) (runtimeapi.MCPProbeReport, error)
	Start(context.Context, runtimeapi.StartRequest) (runtimeapi.WorkloadStatus, error)
	Status(context.Context, string, string, int64) (runtimeapi.WorkloadStatus, error)
	Stop(context.Context, string, string, int64) (runtimeapi.WorkloadStatus, error)
	Delete(context.Context, string, string, int64) error
	ExchangeMCP(
		context.Context,
		string,
		string,
		int64,
		string,
		[]byte,
	) (runtimeapi.MCPExchangeResult, error)
	MCPEvents(
		context.Context,
		string,
		string,
		int64,
		string,
	) (runtimeapi.MCPEventStream, error)
	CloseMCPSession(context.Context, string, string, int64, string) error
}

// Server implements the runtime node's private lifecycle API.
type Server struct {
	config  config.Config
	runtime Runtime
	logger  *slog.Logger
}

// New creates a bounded, token-authenticated runtime HTTP handler.
func New(cfg config.Config, runtime Runtime, logger *slog.Logger) http.Handler {
	server := &Server{config: cfg, runtime: runtime, logger: logger}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", server.health)
	mux.HandleFunc("GET /internal/v1/node/status", server.authenticate(server.nodeStatus))
	mux.HandleFunc("POST /internal/v1/images/prepare", server.authenticate(server.prepare))
	mux.HandleFunc("POST /internal/v1/mcp/probe", server.authenticate(server.probeMCP))
	mux.HandleFunc("POST /internal/v1/workloads", server.authenticate(server.start))
	mux.HandleFunc("GET /internal/v1/workloads/{workloadID}", server.authenticate(server.status))
	mux.HandleFunc("POST /internal/v1/workloads/{workloadID}/stop", server.authenticate(server.stop))
	mux.HandleFunc("DELETE /internal/v1/workloads/{workloadID}", server.authenticate(server.delete))
	mux.HandleFunc("POST /mcp/v1/workloads/{workloadID}", server.authenticateGateway(server.mcpPost))
	mux.HandleFunc("GET /mcp/v1/workloads/{workloadID}", server.authenticateGateway(server.mcpEvents))
	mux.HandleFunc("DELETE /mcp/v1/workloads/{workloadID}", server.authenticateGateway(server.mcpDelete))
	return securityHeaders(mux)
}

func (s *Server) probeMCP(response http.ResponseWriter, request *http.Request) {
	var body runtimeapi.StartRequest
	if err := s.decode(response, request, &body); err != nil {
		return
	}
	report, err := s.runtime.ProbeMCP(request.Context(), body)
	if err != nil {
		s.runtimeError(response, http.StatusBadRequest, err)
		return
	}
	writeJSON(response, http.StatusOK, report)
}

func (s *Server) health(response http.ResponseWriter, request *http.Request) {
	ctx, cancel := context.WithTimeout(request.Context(), 3*time.Second)
	defer cancel()
	status, err := s.runtime.NodeStatus(ctx)
	if err != nil {
		writeError(response, http.StatusServiceUnavailable, "runtime engine unavailable")
		return
	}
	writeJSON(response, http.StatusOK, map[string]any{
		"status":    status.Status,
		"nodeId":    status.NodeID,
		"checkedAt": status.CheckedAt,
	})
}

func (s *Server) nodeStatus(response http.ResponseWriter, request *http.Request) {
	status, err := s.runtime.NodeStatus(request.Context())
	if err != nil {
		s.internalError(response, "read runtime node status", err)
		return
	}
	writeJSON(response, http.StatusOK, status)
}

func (s *Server) prepare(response http.ResponseWriter, request *http.Request) {
	var body struct {
		Image string `json:"image"`
	}
	if err := s.decode(response, request, &body); err != nil {
		return
	}
	status, err := s.runtime.Prepare(request.Context(), body.Image)
	if err != nil {
		writeError(response, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(response, http.StatusOK, status)
}

func (s *Server) start(response http.ResponseWriter, request *http.Request) {
	var body runtimeapi.StartRequest
	if err := s.decode(response, request, &body); err != nil {
		return
	}
	leaseID, fencingToken, err := requestFence(request)
	if err != nil {
		writeError(response, http.StatusBadRequest, err.Error())
		return
	}
	if body.LeaseID != leaseID || body.FencingToken != fencingToken {
		writeError(response, http.StatusConflict, "workload lease fence does not match request")
		return
	}
	status, err := s.runtime.Start(request.Context(), body)
	if err != nil {
		s.runtimeError(response, http.StatusBadRequest, err)
		return
	}
	writeJSON(response, http.StatusCreated, status)
}

func (s *Server) status(response http.ResponseWriter, request *http.Request) {
	leaseID, fencingToken, err := requestFence(request)
	if err != nil {
		writeError(response, http.StatusBadRequest, err.Error())
		return
	}
	status, err := s.runtime.Status(
		request.Context(),
		request.PathValue("workloadID"),
		leaseID,
		fencingToken,
	)
	if err != nil {
		s.runtimeError(response, http.StatusNotFound, err)
		return
	}
	writeJSON(response, http.StatusOK, status)
}

func (s *Server) stop(response http.ResponseWriter, request *http.Request) {
	leaseID, fencingToken, err := requestFence(request)
	if err != nil {
		writeError(response, http.StatusBadRequest, err.Error())
		return
	}
	status, err := s.runtime.Stop(
		request.Context(),
		request.PathValue("workloadID"),
		leaseID,
		fencingToken,
	)
	if err != nil {
		s.runtimeError(response, http.StatusConflict, err)
		return
	}
	writeJSON(response, http.StatusOK, status)
}

func (s *Server) delete(response http.ResponseWriter, request *http.Request) {
	leaseID, fencingToken, err := requestFence(request)
	if err != nil {
		writeError(response, http.StatusBadRequest, err.Error())
		return
	}
	if err := s.runtime.Delete(
		request.Context(),
		request.PathValue("workloadID"),
		leaseID,
		fencingToken,
	); err != nil {
		s.runtimeError(response, http.StatusConflict, err)
		return
	}
	response.WriteHeader(http.StatusNoContent)
}

func (s *Server) mcpPost(response http.ResponseWriter, request *http.Request) {
	leaseID, fencingToken, err := requestFence(request)
	if err != nil {
		writeError(response, http.StatusBadRequest, err.Error())
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(
		response,
		request.Body,
		s.config.MaxMCPMessageBytes,
	))
	if err != nil {
		writeError(response, http.StatusBadRequest, "MCP request body is invalid")
		return
	}
	ctx, cancel := context.WithTimeout(
		request.Context(),
		s.config.MCPRequestTimeout,
	)
	defer cancel()
	result, err := s.runtime.ExchangeMCP(
		ctx,
		request.PathValue("workloadID"),
		leaseID,
		fencingToken,
		strings.TrimSpace(request.Header.Get("Mcp-Session-Id")),
		body,
	)
	if err != nil {
		s.mcpError(response, err)
		return
	}
	response.Header().Set("Mcp-Session-Id", result.SessionID)
	if result.Notification {
		response.WriteHeader(http.StatusAccepted)
		return
	}
	response.Header().Set("Content-Type", "application/json")
	response.WriteHeader(http.StatusOK)
	_, _ = response.Write(result.Message)
}

func (s *Server) mcpEvents(response http.ResponseWriter, request *http.Request) {
	leaseID, fencingToken, err := requestFence(request)
	if err != nil {
		writeError(response, http.StatusBadRequest, err.Error())
		return
	}
	sessionID := strings.TrimSpace(request.Header.Get("Mcp-Session-Id"))
	stream, err := s.runtime.MCPEvents(
		request.Context(),
		request.PathValue("workloadID"),
		leaseID,
		fencingToken,
		sessionID,
	)
	if err != nil {
		s.mcpError(response, err)
		return
	}
	if stream.Release != nil {
		defer stream.Release()
	}
	flusher, ok := response.(http.Flusher)
	if !ok {
		writeError(response, http.StatusInternalServerError, "streaming is unavailable")
		return
	}
	response.Header().Set("Content-Type", "text/event-stream")
	response.Header().Set("Cache-Control", "no-cache, no-transform")
	response.Header().Set("Connection", "keep-alive")
	response.Header().Set("Mcp-Session-Id", sessionID)
	response.WriteHeader(http.StatusOK)
	flusher.Flush()
	heartbeat := time.NewTicker(20 * time.Second)
	defer heartbeat.Stop()
	for {
		select {
		case message := <-stream.Messages:
			if _, err = fmt.Fprintf(
				response,
				"event: message\ndata: %s\n\n",
				message,
			); err != nil {
				return
			}
			flusher.Flush()
		case <-heartbeat.C:
			if _, err = io.WriteString(response, ": keepalive\n\n"); err != nil {
				return
			}
			flusher.Flush()
		case <-stream.Done:
			return
		case <-request.Context().Done():
			return
		}
	}
}

func (s *Server) mcpDelete(response http.ResponseWriter, request *http.Request) {
	leaseID, fencingToken, err := requestFence(request)
	if err != nil {
		writeError(response, http.StatusBadRequest, err.Error())
		return
	}
	err = s.runtime.CloseMCPSession(
		request.Context(),
		request.PathValue("workloadID"),
		leaseID,
		fencingToken,
		strings.TrimSpace(request.Header.Get("Mcp-Session-Id")),
	)
	if err != nil {
		s.mcpError(response, err)
		return
	}
	response.WriteHeader(http.StatusNoContent)
}

func (s *Server) authenticate(next http.HandlerFunc) http.HandlerFunc {
	return func(response http.ResponseWriter, request *http.Request) {
		if s.config.MTLSEnabled {
			if !tlsidentity.VerifiedPeerURI(
				request.TLS,
				s.config.TLSExpectedAIIdentity,
			) {
				writeError(response, http.StatusUnauthorized, "unauthorized")
				return
			}
			ctx, cancel := context.WithTimeout(
				request.Context(),
				s.config.RequestTimeout,
			)
			defer cancel()
			next(response, request.WithContext(ctx))
			return
		}
		provided := strings.TrimSpace(request.Header.Get("X-SimplePoint-Runtime-Token"))
		expected := s.config.InternalToken
		if len(provided) != len(expected) ||
			subtle.ConstantTimeCompare([]byte(provided), []byte(expected)) != 1 {
			writeError(response, http.StatusUnauthorized, "unauthorized")
			return
		}
		ctx, cancel := context.WithTimeout(request.Context(), s.config.RequestTimeout)
		defer cancel()
		next(response, request.WithContext(ctx))
	}
}

func (s *Server) authenticateGateway(next http.HandlerFunc) http.HandlerFunc {
	return func(response http.ResponseWriter, request *http.Request) {
		if s.config.MTLSEnabled {
			if !tlsidentity.VerifiedPeerURI(
				request.TLS,
				s.config.TLSExpectedGatewayIdentity,
			) {
				writeError(response, http.StatusUnauthorized, "unauthorized")
				return
			}
			next(response, request)
			return
		}
		provided := strings.TrimSpace(request.Header.Get("X-SimplePoint-Runtime-Token"))
		expected := s.config.InternalToken
		if len(provided) != len(expected) ||
			subtle.ConstantTimeCompare([]byte(provided), []byte(expected)) != 1 {
			writeError(response, http.StatusUnauthorized, "unauthorized")
			return
		}
		next(response, request)
	}
}

func (s *Server) decode(
	response http.ResponseWriter,
	request *http.Request,
	target any,
) error {
	request.Body = http.MaxBytesReader(response, request.Body, s.config.MaxRequestBytes)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		writeError(response, http.StatusBadRequest, "request body is invalid")
		return err
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		writeError(response, http.StatusBadRequest, "request body must contain one JSON value")
		if err == nil {
			return errors.New("multiple JSON values")
		}
		return err
	}
	return nil
}

func (s *Server) internalError(
	response http.ResponseWriter,
	operation string,
	err error,
) {
	s.logger.Error(operation, "error", err)
	writeError(response, http.StatusServiceUnavailable, "runtime operation failed")
}

func (s *Server) runtimeError(
	response http.ResponseWriter,
	fallbackStatus int,
	err error,
) {
	switch {
	case errors.Is(err, runtimeapi.ErrFenced):
		writeError(response, http.StatusConflict, "workload lease has been fenced")
	case errors.Is(err, runtimeapi.ErrWorkloadNotFound):
		writeError(response, http.StatusNotFound, "workload not found")
	default:
		writeError(response, fallbackStatus, err.Error())
	}
}

func (s *Server) mcpError(response http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, runtimeapi.ErrFenced):
		writeError(response, http.StatusConflict, "workload lease has been fenced")
	case errors.Is(err, runtimeapi.ErrWorkloadNotFound):
		writeError(response, http.StatusNotFound, "workload not found")
	case errors.Is(err, runtimeapi.ErrMCPSessionNotFound):
		writeError(response, http.StatusNotFound, "MCP session not found")
	case errors.Is(err, runtimeapi.ErrMCPTransportUnsupported):
		writeError(response, http.StatusUnprocessableEntity, err.Error())
	case errors.Is(err, context.DeadlineExceeded):
		writeError(response, http.StatusGatewayTimeout, "MCP request timed out")
	default:
		writeError(response, http.StatusBadRequest, err.Error())
	}
}

func requestFence(request *http.Request) (string, int64, error) {
	leaseID := strings.TrimSpace(
		request.Header.Get("X-SimplePoint-Runtime-Lease-Id"),
	)
	if leaseID == "" {
		return "", 0, errors.New("workload lease ID is required")
	}
	fencingToken, err := strconv.ParseInt(
		strings.TrimSpace(
			request.Header.Get("X-SimplePoint-Runtime-Fencing-Token"),
		),
		10,
		64,
	)
	if err != nil || fencingToken <= 0 {
		return "", 0, errors.New("workload fencing token is invalid")
	}
	return leaseID, fencingToken, nil
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		response.Header().Set("Cache-Control", "no-store")
		response.Header().Set("X-Content-Type-Options", "nosniff")
		response.Header().Set("X-Frame-Options", "DENY")
		next.ServeHTTP(response, request)
	})
}

func writeError(response http.ResponseWriter, status int, message string) {
	writeJSON(response, status, map[string]any{
		"status":  status,
		"message": message,
	})
}

func writeJSON(response http.ResponseWriter, status int, value any) {
	response.Header().Set("Content-Type", "application/json")
	response.WriteHeader(status)
	_ = json.NewEncoder(response).Encode(value)
}
