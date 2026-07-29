package runtime

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"

	"github.com/moby/moby/client"
	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/config"
)

const maximumSeccompProfileBytes = 1024 * 1024

type sandboxPolicy struct {
	seccompJSON string
	seccompHash string
}

type sandboxStatus struct {
	securityOptions   []string
	seccompEnforced   bool
	apparmorAvailable bool
}

func loadSandboxPolicy(cfg config.Config) (sandboxPolicy, error) {
	if strings.TrimSpace(cfg.SeccompProfileFile) == "" {
		if cfg.SeccompRequired {
			return sandboxPolicy{}, errors.New("seccomp profile is required")
		}
		return sandboxPolicy{}, nil
	}
	value, err := os.ReadFile(cfg.SeccompProfileFile)
	if err != nil {
		if !cfg.SeccompRequired && errors.Is(err, os.ErrNotExist) {
			return sandboxPolicy{}, nil
		}
		return sandboxPolicy{}, fmt.Errorf("read seccomp profile: %w", err)
	}
	if len(value) == 0 || len(value) > maximumSeccompProfileBytes {
		return sandboxPolicy{}, errors.New("seccomp profile size is invalid")
	}
	var document map[string]any
	if err = json.Unmarshal(value, &document); err != nil {
		return sandboxPolicy{}, fmt.Errorf("parse seccomp profile: %w", err)
	}
	if document["defaultAction"] == nil || document["syscalls"] == nil {
		return sandboxPolicy{}, errors.New(
			"seccomp profile must define defaultAction and syscalls",
		)
	}
	canonical, err := json.Marshal(document)
	if err != nil {
		return sandboxPolicy{}, fmt.Errorf("canonicalize seccomp profile: %w", err)
	}
	digest := sha256.Sum256(canonical)
	return sandboxPolicy{
		seccompJSON: string(canonical),
		seccompHash: "sha256:" + hex.EncodeToString(digest[:]),
	}, nil
}

func (e *Engine) sandboxStatus(ctx context.Context) (sandboxStatus, error) {
	info, err := e.client.Info(ctx, client.InfoOptions{})
	if err != nil {
		return sandboxStatus{}, fmt.Errorf("inspect Docker security options: %w", err)
	}
	status := sandboxStatus{
		securityOptions: append([]string(nil), info.Info.SecurityOptions...),
	}
	for _, option := range info.Info.SecurityOptions {
		normalized := strings.ToLower(strings.TrimSpace(option))
		if strings.Contains(normalized, "name=seccomp") {
			status.seccompEnforced = e.sandbox.seccompJSON != ""
		}
		if strings.Contains(normalized, "name=apparmor") {
			status.apparmorAvailable = true
		}
	}
	if e.config.SeccompRequired && !status.seccompEnforced {
		return sandboxStatus{}, errors.New(
			"Docker Engine does not support the required seccomp policy",
		)
	}
	if e.config.AppArmorRequired &&
		(!e.config.AppArmorEnabled || !status.apparmorAvailable) {
		return sandboxStatus{}, errors.New(
			"Docker Engine does not support the required AppArmor policy",
		)
	}
	return status, nil
}

func (e *Engine) workloadSecurityOptions(
	ctx context.Context,
) ([]string, sandboxStatus, error) {
	status, err := e.sandboxStatus(ctx)
	if err != nil {
		return nil, sandboxStatus{}, err
	}
	options := []string{"no-new-privileges:true"}
	if e.sandbox.seccompJSON != "" {
		options = append(options, "seccomp="+e.sandbox.seccompJSON)
	}
	if e.config.AppArmorEnabled &&
		status.apparmorAvailable &&
		e.config.AppArmorProfile != "" {
		options = append(options, "apparmor="+e.config.AppArmorProfile)
	}
	return options, status, nil
}
