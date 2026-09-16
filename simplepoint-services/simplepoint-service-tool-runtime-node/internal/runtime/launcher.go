package runtime

import (
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/simplepoint1024/open-simplepoint-dashboard/tool-runtime-node/internal/config"
)

const launcherVolumeSubpath = "_runtime"

// InstallLauncher places the trusted static launcher in the shared runtime
// volume without modifying any third-party image.
func InstallLauncher(cfg config.Config) error {
	if !cfg.LauncherEnabled {
		return nil
	}
	source, err := os.Open(cfg.LauncherSource)
	if err != nil {
		return fmt.Errorf("open trusted Runtime launcher: %w", err)
	}
	defer source.Close()
	directory := filepath.Join(cfg.SecretRoot, launcherVolumeSubpath)
	if err = os.MkdirAll(directory, 0o700); err != nil {
		return fmt.Errorf("create Runtime launcher directory: %w", err)
	}
	temporary, err := os.CreateTemp(directory, ".launcher-*")
	if err != nil {
		return fmt.Errorf("create Runtime launcher staging file: %w", err)
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if _, err = io.Copy(temporary, source); err != nil {
		_ = temporary.Close()
		return fmt.Errorf("copy trusted Runtime launcher: %w", err)
	}
	if err = temporary.Chmod(0o500); err != nil {
		_ = temporary.Close()
		return fmt.Errorf("protect trusted Runtime launcher: %w", err)
	}
	if err = temporary.Sync(); err != nil {
		_ = temporary.Close()
		return fmt.Errorf("sync trusted Runtime launcher: %w", err)
	}
	if err = temporary.Close(); err != nil {
		return fmt.Errorf("close trusted Runtime launcher: %w", err)
	}
	if err = os.Rename(temporaryPath, filepath.Join(directory, "launcher")); err != nil {
		return fmt.Errorf("publish trusted Runtime launcher: %w", err)
	}
	return nil
}
