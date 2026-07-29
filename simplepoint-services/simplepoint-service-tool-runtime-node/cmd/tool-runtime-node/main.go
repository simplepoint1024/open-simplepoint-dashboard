package main

import (
	"context"
	"crypto/tls"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/config"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/controlplane"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/httpapi"
	runtimeapi "github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/runtime"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/tlsidentity"
)

var version = "dev"

func main() {
	if len(os.Args) == 2 && os.Args[1] == "healthcheck" {
		runHealthcheck()
		return
	}
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	cfg, err := config.Load()
	if err != nil {
		logger.Error("invalid tool runtime configuration", "error", err)
		os.Exit(1)
	}
	engine, err := runtimeapi.NewEngine(cfg)
	if err != nil {
		logger.Error("initialize OCI runtime engine", "error", err)
		os.Exit(1)
	}
	defer engine.Close()

	server := &http.Server{
		Addr:              cfg.Address,
		Handler:           httpapi.New(cfg, engine, logger),
		ReadHeaderTimeout: cfg.RequestTimeout,
		ReadTimeout:       cfg.RequestTimeout,
		WriteTimeout:      cfg.MCPRequestTimeout,
		IdleTimeout:       cfg.RequestTimeout,
	}
	listener, err := net.Listen("tcp", cfg.Address)
	if err != nil {
		logger.Error("bind tool runtime node", "error", err)
		os.Exit(1)
	}
	if cfg.MTLSEnabled {
		tlsConfig, tlsErr := tlsidentity.ServerConfig(
			cfg.TLSCertificateFile,
			cfg.TLSPrivateKeyFile,
			cfg.TLSCAFile,
			cfg.NodeTLSIdentity(),
		)
		if tlsErr != nil {
			logger.Error("configure tool runtime mTLS", "error", tlsErr)
			os.Exit(1)
		}
		listener = tls.NewListener(listener, tlsConfig)
	}
	stopContext, stop := signal.NotifyContext(
		context.Background(),
		syscall.SIGINT,
		syscall.SIGTERM,
	)
	defer stop()
	controlClient, err := controlplane.New(cfg, version, engine, logger)
	if err != nil {
		logger.Error("initialize runtime control-plane client", "error", err)
		os.Exit(1)
	}
	controlDone := make(chan struct{})
	go func() {
		defer close(controlDone)
		controlClient.Run(stopContext)
	}()
	go func() {
		<-stopContext.Done()
		shutdownContext, cancel := context.WithTimeout(
			context.Background(),
			cfg.ShutdownTimeout,
		)
		defer cancel()
		if shutdownErr := server.Shutdown(shutdownContext); shutdownErr != nil {
			logger.Error("shutdown tool runtime node", "error", shutdownErr)
		}
	}()

	logger.Info(
		"tool runtime node started",
		"version", version,
		"nodeId", cfg.NodeID,
		"address", listener.Addr().String(),
	)
	err = server.Serve(listener)
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Error("tool runtime node stopped unexpectedly", "error", err)
		stop()
		waitForControlPlane(controlDone, cfg.ShutdownTimeout)
		os.Exit(1)
	}
	stop()
	waitForControlPlane(controlDone, cfg.ShutdownTimeout)
}

func runHealthcheck() {
	cfg, err := config.Load()
	if err != nil {
		os.Exit(1)
	}
	scheme := "http"
	client := &http.Client{Timeout: 3 * time.Second}
	if cfg.MTLSEnabled {
		tlsConfig, tlsErr := tlsidentity.ClientConfig(
			cfg.TLSCertificateFile,
			cfg.TLSPrivateKeyFile,
			cfg.TLSCAFile,
			cfg.TLSServerName,
			cfg.NodeTLSIdentity(),
		)
		if tlsErr != nil {
			os.Exit(1)
		}
		client.Transport = &http.Transport{TLSClientConfig: tlsConfig}
		scheme = "https"
	}
	response, err := client.Get(scheme + "://127.0.0.1:2891/health")
	if err != nil {
		os.Exit(1)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		os.Exit(1)
	}
}

func waitForControlPlane(done <-chan struct{}, timeout time.Duration) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
	}
}
