package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"
)

// MCPProbeReport is the sanitized initialize and capability admission result.
type MCPProbeReport struct {
	ProtocolVersion    string `json:"protocolVersion"`
	ToolsSupported     bool   `json:"toolsSupported"`
	ToolCount          int    `json:"toolCount"`
	ResourcesSupported bool   `json:"resourcesSupported"`
	ResourceCount      int    `json:"resourceCount"`
	PromptsSupported   bool   `json:"promptsSupported"`
	PromptCount        int    `json:"promptCount"`
}

type probeResponse struct {
	Result json.RawMessage `json:"result"`
	Error  *probeError     `json:"error"`
}

type probeError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type initializeResult struct {
	ProtocolVersion string `json:"protocolVersion"`
}

// ProbeMCP starts one disposable workload, performs the standard MCP
// initialize/capability exchange, and always reclaims the container.
func (e *Engine) ProbeMCP(
	ctx context.Context,
	request StartRequest,
) (MCPProbeReport, error) {
	identity, err := randomMCPSessionID()
	if err != nil {
		return MCPProbeReport{}, err
	}
	request.WorkloadID = "probe-" + identity[:32]
	request.LeaseID = "lease-" + identity[:32]
	request.ExecutionID = "probe-" + identity[:32]
	request.FencingToken = 1
	if request.TimeoutSeconds == 0 {
		request.TimeoutSeconds = 120
	}
	if request.Transport == "" {
		request.Transport = "stdio"
	}
	if request.Transport != "stdio" &&
		request.Transport != "streamable-http" {
		return MCPProbeReport{}, errors.New(
			"MCP admission transport is unsupported",
		)
	}
	if _, err = e.Start(ctx, request); err != nil {
		return MCPProbeReport{}, fmt.Errorf("start MCP probe workload: %w", err)
	}
	defer func() {
		stopContext, cancelStop := context.WithTimeout(
			context.Background(),
			e.config.ShutdownTimeout,
		)
		_, _ = e.Stop(
			stopContext,
			request.WorkloadID,
			request.LeaseID,
			request.FencingToken,
		)
		cancelStop()
		deleteContext, cancelDelete := context.WithTimeout(
			context.Background(),
			e.config.ShutdownTimeout,
		)
		_ = e.Delete(
			deleteContext,
			request.WorkloadID,
			request.LeaseID,
			request.FencingToken,
		)
		cancelDelete()
	}()
	initialize, sessionID, err := e.probeInitialize(ctx, request)
	if err != nil {
		return MCPProbeReport{}, err
	}
	if initialize.Error != nil {
		return MCPProbeReport{}, errors.New(
			"MCP initialize returned a JSON-RPC error",
		)
	}
	defer func() {
		closeContext, cancelClose := context.WithTimeout(
			context.Background(),
			e.config.ShutdownTimeout,
		)
		defer cancelClose()
		_ = e.CloseMCPSession(
			closeContext,
			request.WorkloadID,
			request.LeaseID,
			request.FencingToken,
			sessionID,
		)
	}()
	var initialized initializeResult
	if err = json.Unmarshal(initialize.Result, &initialized); err != nil ||
		initialized.ProtocolVersion == "" {
		return MCPProbeReport{}, errors.New(
			"MCP initialize response has no protocol version",
		)
	}
	if _, _, err = e.probeRequest(
		ctx,
		request,
		sessionID,
		[]byte(`{"jsonrpc":"2.0","method":"notifications/initialized"}`),
	); err != nil {
		return MCPProbeReport{}, err
	}
	toolsSupported, tools, err := e.probeCapability(
		ctx, request, sessionID, "tools/list", "tools",
	)
	if err != nil || !toolsSupported {
		return MCPProbeReport{}, errors.New(
			"MCP admission requires tools/list support",
		)
	}
	resourcesSupported, resources, err := e.probeCapability(
		ctx, request, sessionID, "resources/list", "resources",
	)
	if err != nil {
		return MCPProbeReport{}, err
	}
	promptsSupported, prompts, err := e.probeCapability(
		ctx, request, sessionID, "prompts/list", "prompts",
	)
	if err != nil {
		return MCPProbeReport{}, err
	}
	return MCPProbeReport{
		ProtocolVersion:    initialized.ProtocolVersion,
		ToolsSupported:     true,
		ToolCount:          tools,
		ResourcesSupported: resourcesSupported,
		ResourceCount:      resources,
		PromptsSupported:   promptsSupported,
		PromptCount:        prompts,
	}, nil
}

func (e *Engine) probeInitialize(
	ctx context.Context,
	request StartRequest,
) (probeResponse, string, error) {
	message := []byte(`{"jsonrpc":"2.0","id":"probe-initialize","method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"open-simplepoint-admission","version":"1"}}}`)
	return retryProbeInitialize(
		ctx,
		5*time.Second,
		100*time.Millisecond,
		func() (probeResponse, string, error) {
			return e.probeRequest(ctx, request, "", message)
		},
	)
}

// retryProbeInitialize absorbs the short interval between Docker accepting a
// container start and a slower MCP process becoming attachable. Both stdio and
// HTTP servers can need this grace period; limiting retries to HTTP made valid
// Python stdio servers fail admission nondeterministically.
func retryProbeInitialize(
	ctx context.Context,
	window time.Duration,
	interval time.Duration,
	operation func() (probeResponse, string, error),
) (probeResponse, string, error) {
	deadline := time.Now().Add(window)
	for {
		response, sessionID, err := operation()
		if err == nil || !time.Now().Before(deadline) {
			return response, sessionID, err
		}
		timer := time.NewTimer(interval)
		select {
		case <-timer.C:
		case <-ctx.Done():
			if !timer.Stop() {
				<-timer.C
			}
			return probeResponse{}, "", ctx.Err()
		}
	}
}

func (e *Engine) probeCapability(
	ctx context.Context,
	request StartRequest,
	sessionID string,
	method string,
	field string,
) (bool, int, error) {
	message, err := json.Marshal(map[string]any{
		"jsonrpc": "2.0",
		"id":      "probe-" + field,
		"method":  method,
		"params":  map[string]any{},
	})
	if err != nil {
		return false, 0, err
	}
	response, _, err := e.probeRequest(
		ctx, request, sessionID, message,
	)
	if err != nil {
		return false, 0, err
	}
	if response.Error != nil {
		if response.Error.Code == -32601 {
			return false, 0, nil
		}
		return false, 0, fmt.Errorf(
			"MCP %s returned a JSON-RPC error", method,
		)
	}
	var result map[string]json.RawMessage
	if err = json.Unmarshal(response.Result, &result); err != nil {
		return false, 0, fmt.Errorf("decode MCP %s result: %w", method, err)
	}
	var values []json.RawMessage
	if err = json.Unmarshal(result[field], &values); err != nil {
		return false, 0, fmt.Errorf("decode MCP %s list: %w", method, err)
	}
	return true, len(values), nil
}

func (e *Engine) probeRequest(
	ctx context.Context,
	request StartRequest,
	sessionID string,
	message []byte,
) (probeResponse, string, error) {
	result, err := e.ExchangeMCP(
		ctx,
		request.WorkloadID,
		request.LeaseID,
		request.FencingToken,
		sessionID,
		message,
	)
	if err != nil {
		return probeResponse{}, sessionID, err
	}
	if result.Notification {
		return probeResponse{}, result.SessionID, nil
	}
	var response probeResponse
	if err = json.Unmarshal(result.Message, &response); err != nil {
		return probeResponse{}, result.SessionID, errors.New(
			"MCP probe response is invalid JSON-RPC",
		)
	}
	return response, result.SessionID, nil
}
