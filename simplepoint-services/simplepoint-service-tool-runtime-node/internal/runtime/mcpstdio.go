package runtime

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"

	"github.com/moby/moby/api/pkg/stdcopy"
	"github.com/moby/moby/client"
)

var (
	// ErrMCPSessionNotFound identifies an absent or closed stdio bridge session.
	ErrMCPSessionNotFound = errors.New("runtime MCP session not found")
	// ErrMCPTransportUnsupported identifies a workload that is not a stdio server.
	ErrMCPTransportUnsupported = errors.New("runtime workload is not a stdio MCP server")
)

const maximumPendingMCPEvents = 128

// MCPExchangeResult is one Streamable HTTP response bridged to stdio.
type MCPExchangeResult struct {
	SessionID    string
	Message      []byte
	Notification bool
}

// MCPEventStream carries unsolicited server messages and a close signal.
type MCPEventStream struct {
	Messages <-chan []byte
	Done     <-chan struct{}
	Release  func()
}

type mcpSessionRegistry struct {
	mu         sync.RWMutex
	byID       map[string]*mcpSession
	byWorkload map[string]*mcpSession
}

type mcpSession struct {
	id            string
	workloadID    string
	leaseID       string
	fencingToken  int64
	attach        client.ContainerAttachResult
	maxBytes      int64
	writeMu       sync.Mutex
	pendingMu     sync.Mutex
	pending       map[string]chan []byte
	eventMu       sync.Mutex
	eventStreams  int
	eventObserved bool
	events        chan []byte
	done          chan struct{}
	closeOnce     sync.Once
}

type jsonRPCEnvelope struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
	Result  json.RawMessage `json:"result"`
	Error   json.RawMessage `json:"error"`
}

func newMCPSessionRegistry() *mcpSessionRegistry {
	return &mcpSessionRegistry{
		byID:       make(map[string]*mcpSession),
		byWorkload: make(map[string]*mcpSession),
	}
}

// ExchangeMCP forwards one JSON-RPC message to a fenced stdio workload.
func (e *Engine) ExchangeMCP(
	ctx context.Context,
	workloadID string,
	leaseID string,
	fencingToken int64,
	sessionID string,
	message []byte,
) (MCPExchangeResult, error) {
	envelope, err := validateMCPMessage(message, e.config.MaxMCPMessageBytes)
	if err != nil {
		return MCPExchangeResult{}, err
	}
	transport, _, err := e.workloadMCPTransport(
		ctx, workloadID, leaseID, fencingToken,
	)
	if err != nil {
		return MCPExchangeResult{}, err
	}
	if transport == "streamable-http" {
		return e.exchangeHTTPMCP(
			ctx,
			workloadID,
			leaseID,
			fencingToken,
			sessionID,
			message,
		)
	}
	session, err := e.resolveMCPSession(
		ctx,
		workloadID,
		leaseID,
		fencingToken,
		sessionID,
		envelope.Method == "initialize",
	)
	if err != nil {
		return MCPExchangeResult{}, err
	}
	key := messageID(envelope.ID)
	if key == "" {
		if err = session.write(message); err != nil {
			return MCPExchangeResult{}, err
		}
		return MCPExchangeResult{
			SessionID:    session.id,
			Notification: true,
		}, nil
	}
	response := make(chan []byte, 1)
	session.pendingMu.Lock()
	if _, exists := session.pending[key]; exists {
		session.pendingMu.Unlock()
		return MCPExchangeResult{}, errors.New("MCP request ID is already pending")
	}
	session.pending[key] = response
	session.pendingMu.Unlock()
	defer func() {
		session.pendingMu.Lock()
		delete(session.pending, key)
		session.pendingMu.Unlock()
	}()
	if err = session.write(message); err != nil {
		return MCPExchangeResult{}, err
	}
	select {
	case value := <-response:
		return MCPExchangeResult{
			SessionID: session.id,
			Message:   value,
		}, nil
	case <-session.done:
		return MCPExchangeResult{}, ErrMCPSessionNotFound
	case <-ctx.Done():
		return MCPExchangeResult{}, ctx.Err()
	}
}

// MCPEvents returns unsolicited messages for one fenced session.
func (e *Engine) MCPEvents(
	ctx context.Context,
	workloadID string,
	leaseID string,
	fencingToken int64,
	sessionID string,
) (MCPEventStream, error) {
	transport, _, err := e.workloadMCPTransport(
		ctx, workloadID, leaseID, fencingToken,
	)
	if err != nil {
		return MCPEventStream{}, err
	}
	if transport == "streamable-http" {
		return e.httpMCPEvents(
			ctx, workloadID, leaseID, fencingToken, sessionID,
		)
	}
	if _, err := e.Status(ctx, workloadID, leaseID, fencingToken); err != nil {
		return MCPEventStream{}, err
	}
	session := e.mcpSessions.get(sessionID)
	if session == nil || !session.matches(workloadID, leaseID, fencingToken) {
		return MCPEventStream{}, ErrMCPSessionNotFound
	}
	release, ok := session.acquireEventStream()
	if !ok {
		return MCPEventStream{}, ErrMCPSessionNotFound
	}
	return MCPEventStream{
		Messages: session.events,
		Done:     session.done,
		Release:  release,
	}, nil
}

// CloseMCPSession detaches one fenced stdio session.
func (e *Engine) CloseMCPSession(
	ctx context.Context,
	workloadID string,
	leaseID string,
	fencingToken int64,
	sessionID string,
) error {
	transport, _, err := e.workloadMCPTransport(
		ctx, workloadID, leaseID, fencingToken,
	)
	if err != nil {
		return err
	}
	if transport == "streamable-http" {
		return e.closeHTTPMCPSession(
			ctx, workloadID, leaseID, fencingToken, sessionID,
		)
	}
	if _, err := e.Status(ctx, workloadID, leaseID, fencingToken); err != nil {
		return err
	}
	session := e.mcpSessions.get(sessionID)
	if session == nil || !session.matches(workloadID, leaseID, fencingToken) {
		return ErrMCPSessionNotFound
	}
	e.mcpSessions.remove(session)
	session.close()
	return nil
}

func (e *Engine) resolveMCPSession(
	ctx context.Context,
	workloadID string,
	leaseID string,
	fencingToken int64,
	sessionID string,
	allowCreate bool,
) (*mcpSession, error) {
	if sessionID != "" {
		session := e.mcpSessions.get(sessionID)
		if session == nil || !session.matches(workloadID, leaseID, fencingToken) {
			return nil, ErrMCPSessionNotFound
		}
		return session, nil
	}
	if !allowCreate {
		return nil, errors.New("MCP-Session-Id is required")
	}
	if existing := e.mcpSessions.workload(workloadID); existing != nil {
		if existing.matches(workloadID, leaseID, fencingToken) {
			if !existing.takeoverAllowed() {
				return nil, errors.New("runtime workload already has an active MCP session")
			}
		}
		e.mcpSessions.remove(existing)
		existing.close()
	}
	status, err := e.Status(ctx, workloadID, leaseID, fencingToken)
	if err != nil {
		return nil, err
	}
	if status.State != "running" {
		return nil, fmt.Errorf(
			"runtime workload is not running (state %s, exit code %d)",
			status.State,
			status.ExitCode,
		)
	}
	inspect, err := e.client.ContainerInspect(
		ctx,
		e.containerName(workloadID),
		client.ContainerInspectOptions{},
	)
	if err != nil {
		return nil, fmt.Errorf("inspect stdio MCP workload: %w", err)
	}
	if inspect.Container.Config == nil ||
		inspect.Container.Config.Labels[labelMCPTransport] != "stdio" ||
		!inspect.Container.Config.OpenStdin {
		return nil, ErrMCPTransportUnsupported
	}
	attached, err := e.client.ContainerAttach(
		ctx,
		inspect.Container.ID,
		client.ContainerAttachOptions{
			Stream: true,
			Stdin:  true,
			Stdout: true,
			Stderr: true,
		},
	)
	if err != nil {
		return nil, fmt.Errorf("attach stdio MCP workload: %w", err)
	}
	id, err := randomMCPSessionID()
	if err != nil {
		attached.Close()
		return nil, err
	}
	session := &mcpSession{
		id:           id,
		workloadID:   workloadID,
		leaseID:      leaseID,
		fencingToken: fencingToken,
		attach:       attached,
		maxBytes:     e.config.MaxMCPMessageBytes,
		pending:      make(map[string]chan []byte),
		events:       make(chan []byte, maximumPendingMCPEvents),
		done:         make(chan struct{}),
	}
	e.mcpSessions.add(session)
	go e.readMCPOutput(session)
	return session, nil
}

func (e *Engine) readMCPOutput(session *mcpSession) {
	outputReader, outputWriter := io.Pipe()
	go func() {
		_, err := stdcopy.StdCopy(
			outputWriter,
			io.Discard,
			session.attach.Reader,
		)
		_ = outputWriter.CloseWithError(err)
	}()
	scanner := bufio.NewScanner(outputReader)
	scanner.Buffer(make([]byte, 64*1024), int(session.maxBytes))
	for scanner.Scan() {
		message := bytes.TrimSpace(scanner.Bytes())
		envelope, err := validateMCPMessage(message, session.maxBytes)
		if err != nil {
			continue
		}
		key := messageID(envelope.ID)
		if envelope.Method == "" && key != "" {
			session.pendingMu.Lock()
			target := session.pending[key]
			session.pendingMu.Unlock()
			if target != nil {
				target <- append([]byte(nil), message...)
				continue
			}
		}
		select {
		case session.events <- append([]byte(nil), message...):
		default:
			e.mcpSessions.remove(session)
			session.close()
			return
		}
	}
	e.mcpSessions.remove(session)
	session.close()
}

func validateMCPMessage(
	message []byte,
	maxBytes int64,
) (jsonRPCEnvelope, error) {
	message = bytes.TrimSpace(message)
	if len(message) == 0 || int64(len(message)) > maxBytes {
		return jsonRPCEnvelope{}, errors.New("MCP message size is invalid")
	}
	var envelope jsonRPCEnvelope
	decoder := json.NewDecoder(bytes.NewReader(message))
	if err := decoder.Decode(&envelope); err != nil {
		return jsonRPCEnvelope{}, errors.New("MCP message is invalid JSON")
	}
	var trailing json.RawMessage
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return jsonRPCEnvelope{}, errors.New("MCP message contains trailing JSON")
	}
	if envelope.JSONRPC != "2.0" {
		return jsonRPCEnvelope{}, errors.New("MCP JSON-RPC version is invalid")
	}
	isRequest := envelope.Method != ""
	isResponse := len(envelope.ID) > 0 &&
		(len(envelope.Result) > 0 || len(envelope.Error) > 0)
	if isRequest == isResponse {
		return jsonRPCEnvelope{}, errors.New("MCP JSON-RPC envelope is invalid")
	}
	return envelope, nil
}

func messageID(value json.RawMessage) string {
	return string(bytes.TrimSpace(value))
}

func randomMCPSessionID() (string, error) {
	value := make([]byte, 32)
	if _, err := rand.Read(value); err != nil {
		return "", fmt.Errorf("create MCP session ID: %w", err)
	}
	return hex.EncodeToString(value), nil
}

func (s *mcpSession) write(message []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	select {
	case <-s.done:
		return ErrMCPSessionNotFound
	default:
	}
	payload := append(append([]byte(nil), bytes.TrimSpace(message)...), '\n')
	if _, err := s.attach.Conn.Write(payload); err != nil {
		s.close()
		return fmt.Errorf("write stdio MCP request: %w", err)
	}
	return nil
}

func (s *mcpSession) matches(
	workloadID string,
	leaseID string,
	fencingToken int64,
) bool {
	return s.workloadID == workloadID &&
		s.leaseID == leaseID &&
		s.fencingToken == fencingToken
}

func (s *mcpSession) close() {
	s.closeOnce.Do(func() {
		close(s.done)
		s.attach.Close()
	})
}

func (s *mcpSession) closed() bool {
	select {
	case <-s.done:
		return true
	default:
		return false
	}
}

func (s *mcpSession) acquireEventStream() (func(), bool) {
	s.eventMu.Lock()
	defer s.eventMu.Unlock()
	if s.closed() {
		return nil, false
	}
	s.eventObserved = true
	s.eventStreams++
	var once sync.Once
	return func() {
		once.Do(func() {
			s.eventMu.Lock()
			defer s.eventMu.Unlock()
			if s.eventStreams > 0 {
				s.eventStreams--
			}
		})
	}, true
}

func (s *mcpSession) takeoverAllowed() bool {
	s.eventMu.Lock()
	orphaned := s.eventObserved && s.eventStreams == 0
	s.eventMu.Unlock()
	if !orphaned {
		return false
	}
	s.pendingMu.Lock()
	defer s.pendingMu.Unlock()
	return len(s.pending) == 0
}

func (r *mcpSessionRegistry) add(session *mcpSession) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.byID[session.id] = session
	r.byWorkload[session.workloadID] = session
}

func (r *mcpSessionRegistry) get(id string) *mcpSession {
	r.mu.RLock()
	defer r.mu.RUnlock()
	session := r.byID[id]
	if session != nil && session.closed() {
		return nil
	}
	return session
}

func (r *mcpSessionRegistry) workload(workloadID string) *mcpSession {
	r.mu.RLock()
	defer r.mu.RUnlock()
	session := r.byWorkload[workloadID]
	if session != nil && session.closed() {
		return nil
	}
	return session
}

func (r *mcpSessionRegistry) remove(session *mcpSession) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.byID[session.id] == session {
		delete(r.byID, session.id)
	}
	if r.byWorkload[session.workloadID] == session {
		delete(r.byWorkload, session.workloadID)
	}
}

func (r *mcpSessionRegistry) closeWorkload(workloadID string) {
	session := r.workload(workloadID)
	if session != nil {
		r.remove(session)
		session.close()
	}
}

func (r *mcpSessionRegistry) closeAll() {
	r.mu.Lock()
	sessions := make([]*mcpSession, 0, len(r.byID))
	for _, session := range r.byID {
		sessions = append(sessions, session)
	}
	clear(r.byID)
	clear(r.byWorkload)
	r.mu.Unlock()
	for _, session := range sessions {
		session.close()
	}
}
