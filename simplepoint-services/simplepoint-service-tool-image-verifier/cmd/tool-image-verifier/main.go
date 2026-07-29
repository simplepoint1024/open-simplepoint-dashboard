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

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/admission"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/config"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/httpapi"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-image-verifier/internal/tlsidentity"
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
		logger.Error("invalid tool image verifier configuration", "error", err)
		os.Exit(1)
	}
	verifier := admission.New(cfg, nil)
	server := &http.Server{
		Addr:              cfg.Address,
		Handler:           httpapi.New(cfg, verifier, logger),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       cfg.RequestTimeout,
		WriteTimeout:      cfg.RequestTimeout,
		IdleTimeout:       30 * time.Second,
	}
	listener, err := net.Listen("tcp", cfg.Address)
	if err != nil {
		logger.Error("bind tool image verifier", "error", err)
		os.Exit(1)
	}
	if cfg.MTLSEnabled {
		tlsConfig, tlsErr := tlsidentity.ServerConfig(
			cfg.TLSCertificateFile,
			cfg.TLSPrivateKeyFile,
			cfg.TLSCAFile,
			cfg.TLSIdentity,
		)
		if tlsErr != nil {
			logger.Error("configure tool image verifier mTLS", "error", tlsErr)
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
	go func() {
		<-stopContext.Done()
		shutdownContext, cancel := context.WithTimeout(
			context.Background(),
			cfg.ShutdownTimeout,
		)
		defer cancel()
		if shutdownErr := server.Shutdown(shutdownContext); shutdownErr != nil {
			logger.Error("shutdown tool image verifier", "error", shutdownErr)
		}
	}()

	logger.Info(
		"tool image verifier started",
		"version", version,
		"address", listener.Addr().String(),
		"policyHash", cfg.PolicyHash,
	)
	err = server.Serve(listener)
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Error("tool image verifier stopped unexpectedly", "error", err)
		os.Exit(1)
	}
}

func runHealthcheck() {
	cfg, err := config.Load()
	if err != nil {
		os.Exit(1)
	}
	scheme := "http"
	client := &http.Client{Timeout: 5 * time.Second}
	if cfg.MTLSEnabled {
		tlsConfig, tlsErr := tlsidentity.ClientConfig(
			cfg.TLSCertificateFile,
			cfg.TLSPrivateKeyFile,
			cfg.TLSCAFile,
			"tool-image-verifier",
			cfg.TLSIdentity,
		)
		if tlsErr != nil {
			os.Exit(1)
		}
		client.Transport = &http.Transport{TLSClientConfig: tlsConfig}
		scheme = "https"
	}
	response, err := client.Get(scheme + "://127.0.0.1:2893/health")
	if err != nil {
		os.Exit(1)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		os.Exit(1)
	}
}
