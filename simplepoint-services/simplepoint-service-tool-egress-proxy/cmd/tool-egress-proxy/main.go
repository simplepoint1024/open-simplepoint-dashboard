package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-egress-proxy/internal/config"
	egressproxy "github.com/simplepoint1024/open-simplepoint-dashboard/tool-egress-proxy/internal/proxy"
)

var version = "dev"

func main() {
	cfg, err := config.Load()
	if err != nil {
		fail("load configuration", err)
	}
	if len(os.Args) == 2 && os.Args[1] == "healthcheck" {
		if err = healthcheck(cfg); err != nil {
			fail("healthcheck", err)
		}
		return
	}
	if len(os.Args) != 1 {
		fail("parse command", errors.New("unsupported command"))
	}
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	server := &http.Server{
		Addr:              cfg.Address,
		Handler:           egressproxy.New(cfg, nil, logger),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      0,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    16 * 1024,
	}
	ctx, stop := signal.NotifyContext(
		context.Background(),
		syscall.SIGINT,
		syscall.SIGTERM,
	)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(
			context.Background(),
			10*time.Second,
		)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()
	logger.Info(
		"tool egress proxy listening",
		"address", cfg.Address,
		"version", version,
	)
	err = server.ListenAndServe()
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		fail("serve tool egress proxy", err)
	}
}

func healthcheck(cfg config.Config) error {
	client := &http.Client{Timeout: 3 * time.Second}
	response, err := client.Get(
		"http://127.0.0.1:" + strconv.Itoa(cfg.Port()) + "/health",
	)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected HTTP status %d", response.StatusCode)
	}
	return nil
}

func fail(operation string, err error) {
	slog.Error(operation, "error", err)
	os.Exit(1)
}
