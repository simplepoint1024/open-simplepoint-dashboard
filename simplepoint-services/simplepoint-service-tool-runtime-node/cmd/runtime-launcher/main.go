package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
)

const (
	configEnvironment  = "SIMPLEPOINT_LAUNCHER_CONFIG"
	maximumConfigBytes = 64 * 1024
	managedVolumePath  = "/managed-volume"
)

var environmentPattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]{0,127}$`)

type launcherConfig struct {
	Command           []string          `json:"command"`
	SecretEnvironment map[string]string `json:"secretEnvironment"`
}

func main() {
	if err := run(); err != nil {
		_, _ = fmt.Fprintln(os.Stderr, "simplepoint runtime launcher:", err)
		os.Exit(127)
	}
}

func run() error {
	if len(os.Args) >= 2 && os.Args[1] == "initialize-managed-volume" {
		if len(os.Args) != 3 {
			return errors.New("managed volume owner is required")
		}
		return initializeManagedVolume(os.Args[2])
	}
	path := strings.TrimSpace(os.Getenv(configEnvironment))
	if !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return errors.New("launcher config path is invalid")
	}
	value, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read launcher config: %w", err)
	}
	if len(value) == 0 || len(value) > maximumConfigBytes {
		return errors.New("launcher config size is invalid")
	}
	var cfg launcherConfig
	if err = json.Unmarshal(value, &cfg); err != nil {
		return fmt.Errorf("decode launcher config: %w", err)
	}
	if len(cfg.Command) == 0 || len(cfg.Command) > 128 {
		return errors.New("launcher command is invalid")
	}
	for _, argument := range cfg.Command {
		if argument == "" || strings.ContainsRune(argument, '\x00') {
			return errors.New("launcher command argument is invalid")
		}
	}
	for name, secretPath := range cfg.SecretEnvironment {
		if !environmentPattern.MatchString(name) ||
			!filepath.IsAbs(secretPath) || filepath.Clean(secretPath) != secretPath {
			return errors.New("launcher secret environment binding is invalid")
		}
		secret, readErr := os.ReadFile(secretPath)
		if readErr != nil {
			return fmt.Errorf("read launcher secret binding: %w", readErr)
		}
		if err = os.Setenv(name, string(secret)); err != nil {
			return fmt.Errorf("set launcher child environment: %w", err)
		}
	}
	executable, err := exec.LookPath(cfg.Command[0])
	if err != nil {
		return fmt.Errorf("resolve launcher target: %w", err)
	}
	return syscall.Exec(executable, cfg.Command, os.Environ())
}

// initializeManagedVolume prepares only the fixed mount used by the Runtime's
// trusted one-shot storage initializer. It accepts no user-controlled path or
// ownership value.
func initializeManagedVolume(owner string) error {
	return initializeManagedVolumeAtOwner(managedVolumePath, owner)
}

func initializeManagedVolumeAt(path string) error {
	return initializeManagedVolumeAtOwner(path, "65532:65532")
}

func initializeManagedVolumeAtOwner(path string, owner string) error {
	info, err := os.Lstat(path)
	if err != nil {
		return fmt.Errorf("inspect managed volume: %w", err)
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return errors.New("managed volume target is not a directory")
	}
	uid, gid, err := resolveOwner(owner)
	if err != nil {
		return err
	}
	if err = os.Chown(path, uid, gid); err != nil {
		return fmt.Errorf("assign managed volume ownership: %w", err)
	}
	if err = os.Chmod(path, 0o770); err != nil {
		return fmt.Errorf("protect managed volume permissions: %w", err)
	}
	return nil
}

func resolveOwner(value string) (int, int, error) {
	parts := strings.Split(strings.TrimSpace(value), ":")
	if len(parts) > 2 || parts[0] == "" {
		return 0, 0, errors.New("managed volume owner is invalid")
	}
	account, err := lookupUser(parts[0])
	if err != nil {
		return 0, 0, errors.New("managed volume user is unavailable")
	}
	uid, err := strconv.Atoi(account.Uid)
	if err != nil || uid < 0 {
		return 0, 0, errors.New("managed volume user ID is invalid")
	}
	gidValue := account.Gid
	if len(parts) == 2 {
		group, groupErr := lookupGroup(parts[1])
		if groupErr != nil {
			return 0, 0, errors.New("managed volume group is unavailable")
		}
		gidValue = group.Gid
	}
	gid, err := strconv.Atoi(gidValue)
	if err != nil || gid < 0 {
		return 0, 0, errors.New("managed volume group ID is invalid")
	}
	return uid, gid, nil
}

func lookupUser(value string) (*user.User, error) {
	if _, err := strconv.Atoi(value); err == nil {
		return &user.User{Uid: value, Gid: value}, nil
	}
	return user.Lookup(value)
}

func lookupGroup(value string) (*user.Group, error) {
	if _, err := strconv.Atoi(value); err == nil {
		return &user.Group{Gid: value}, nil
	}
	return user.LookupGroup(value)
}
