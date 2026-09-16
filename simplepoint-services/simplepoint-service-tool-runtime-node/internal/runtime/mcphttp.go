package runtime

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"

	"github.com/moby/moby/client"
)

var containerMCPHTTPClient = &http.Client{Transport: &http.Transport{
	Proxy:               nil,
	DisableCompression:  true,
	MaxIdleConns:        64,
	MaxIdleConnsPerHost: 8,
}}

func (e *Engine) exchangeHTTPMCP(
	ctx context.Context,
	workloadID string,
	leaseID string,
	fencingToken int64,
	sessionID string,
	message []byte,
) (MCPExchangeResult, error) {
	_, endpoint, err := e.workloadMCPTransport(
		ctx, workloadID, leaseID, fencingToken,
	)
	if err != nil {
		return MCPExchangeResult{}, err
	}
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		endpoint,
		bytes.NewReader(message),
	)
	if err != nil {
		return MCPExchangeResult{}, errors.New("create container MCP request")
	}
	setMCPHTTPHeaders(request, sessionID)
	response, err := containerMCPHTTPClient.Do(request)
	if err != nil {
		return MCPExchangeResult{}, errors.New("container MCP request failed")
	}
	defer response.Body.Close()
	resultSession := strings.TrimSpace(response.Header.Get("Mcp-Session-Id"))
	if resultSession == "" {
		resultSession = sessionID
	}
	if response.StatusCode == http.StatusAccepted {
		return MCPExchangeResult{
			SessionID:    resultSession,
			Notification: true,
		}, nil
	}
	if response.StatusCode != http.StatusOK {
		return MCPExchangeResult{}, fmt.Errorf(
			"container MCP returned HTTP %d",
			response.StatusCode,
		)
	}
	body, err := io.ReadAll(io.LimitReader(
		response.Body,
		e.config.MaxMCPMessageBytes+1,
	))
	if err != nil || int64(len(body)) > e.config.MaxMCPMessageBytes {
		return MCPExchangeResult{}, errors.New("container MCP response is invalid")
	}
	if _, err = validateMCPMessage(body, e.config.MaxMCPMessageBytes); err != nil {
		return MCPExchangeResult{}, errors.New("container MCP response is invalid")
	}
	return MCPExchangeResult{
		SessionID: resultSession,
		Message:   body,
	}, nil
}

func (e *Engine) httpMCPEvents(
	ctx context.Context,
	workloadID string,
	leaseID string,
	fencingToken int64,
	sessionID string,
) (MCPEventStream, error) {
	if sessionID == "" {
		return MCPEventStream{}, errors.New("MCP-Session-Id is required")
	}
	_, endpoint, err := e.workloadMCPTransport(
		ctx, workloadID, leaseID, fencingToken,
	)
	if err != nil {
		return MCPEventStream{}, err
	}
	streamContext, cancel := context.WithCancel(ctx)
	request, err := http.NewRequestWithContext(
		streamContext,
		http.MethodGet,
		endpoint,
		nil,
	)
	if err != nil {
		cancel()
		return MCPEventStream{}, errors.New("create container MCP event request")
	}
	setMCPHTTPHeaders(request, sessionID)
	response, err := containerMCPHTTPClient.Do(request)
	if err != nil {
		cancel()
		return MCPEventStream{}, errors.New("container MCP event request failed")
	}
	if response.StatusCode != http.StatusOK ||
		!strings.HasPrefix(response.Header.Get("Content-Type"), "text/event-stream") {
		_ = response.Body.Close()
		cancel()
		return MCPEventStream{}, errors.New("container MCP event stream is unavailable")
	}
	messages := make(chan []byte, maximumPendingMCPEvents)
	done := make(chan struct{})
	var once sync.Once
	release := func() {
		once.Do(func() {
			cancel()
			_ = response.Body.Close()
		})
	}
	go func() {
		defer close(done)
		defer release()
		scanMCPEventStream(
			response.Body,
			e.config.MaxMCPMessageBytes,
			messages,
		)
	}()
	return MCPEventStream{
		Messages: messages,
		Done:     done,
		Release:  release,
	}, nil
}

func (e *Engine) closeHTTPMCPSession(
	ctx context.Context,
	workloadID string,
	leaseID string,
	fencingToken int64,
	sessionID string,
) error {
	_, endpoint, err := e.workloadMCPTransport(
		ctx, workloadID, leaseID, fencingToken,
	)
	if err != nil {
		return err
	}
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodDelete,
		endpoint,
		nil,
	)
	if err != nil {
		return errors.New("create container MCP close request")
	}
	setMCPHTTPHeaders(request, sessionID)
	response, err := containerMCPHTTPClient.Do(request)
	if err != nil {
		return errors.New("container MCP close request failed")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusNoContent &&
		response.StatusCode != http.StatusOK {
		return fmt.Errorf(
			"container MCP close returned HTTP %d",
			response.StatusCode,
		)
	}
	return nil
}

func (e *Engine) workloadMCPTransport(
	ctx context.Context,
	workloadID string,
	leaseID string,
	fencingToken int64,
) (string, string, error) {
	status, err := e.Status(ctx, workloadID, leaseID, fencingToken)
	if err != nil {
		return "", "", err
	}
	inspect, err := e.client.ContainerInspect(
		ctx,
		status.ContainerID,
		client.ContainerInspectOptions{},
	)
	if err != nil || inspect.Container.Config == nil {
		return "", "", errors.New("inspect MCP workload transport")
	}
	transport := inspect.Container.Config.Labels[labelMCPTransport]
	if transport == "stdio" {
		return transport, "", nil
	}
	if transport != "streamable-http" ||
		inspect.Container.NetworkSettings == nil {
		return "", "", ErrMCPTransportUnsupported
	}
	port, err := strconv.Atoi(inspect.Container.Config.Labels[labelMCPPort])
	path := inspect.Container.Config.Labels[labelMCPPath]
	networkSettings := inspect.Container.NetworkSettings.Networks[e.config.TransportNetworkName]
	if err != nil || port < 1 || port > 65535 ||
		!validTransportPath(path) || networkSettings == nil ||
		!networkSettings.IPAddress.IsValid() {
		return "", "", errors.New("container MCP endpoint is unavailable")
	}
	endpoint := url.URL{
		Scheme: "http",
		Host: net.JoinHostPort(
			networkSettings.IPAddress.String(),
			strconv.Itoa(port),
		),
		Path: path,
	}
	return transport, endpoint.String(), nil
}

func setMCPHTTPHeaders(request *http.Request, sessionID string) {
	request.Header.Set("Accept", "application/json, text/event-stream")
	if request.Method == http.MethodPost {
		request.Header.Set("Content-Type", "application/json")
	}
	if sessionID != "" {
		request.Header.Set("Mcp-Session-Id", sessionID)
	}
}

func scanMCPEventStream(
	reader io.Reader,
	maximumBytes int64,
	messages chan<- []byte,
) {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), int(maximumBytes))
	data := make([]string, 0, 1)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			if len(data) > 0 {
				message := []byte(strings.Join(data, "\n"))
				if _, err := validateMCPMessage(message, maximumBytes); err == nil {
					select {
					case messages <- message:
					default:
						return
					}
				}
				data = data[:0]
			}
			continue
		}
		if value, found := strings.CutPrefix(line, "data:"); found {
			data = append(data, strings.TrimSpace(value))
		}
	}
}
