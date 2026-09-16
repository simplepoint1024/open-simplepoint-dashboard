package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestInitializeManagedVolumeRejectsSymlink(t *testing.T) {
	directory := t.TempDir()
	target := filepath.Join(directory, "target")
	if err := os.Mkdir(target, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(directory, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	if err := initializeManagedVolumeAt(link); err == nil {
		t.Fatal("expected managed volume symlink to be rejected")
	}
}

func TestInitializeManagedVolumeAssignsFixedIdentity(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("ownership assertion requires the containerized root test runner")
	}
	directory := t.TempDir()
	if err := initializeManagedVolumeAt(directory); err != nil {
		t.Fatalf("initialize managed volume: %v", err)
	}
	info, err := os.Stat(directory)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o770 {
		t.Fatalf("unexpected managed volume mode: %o", info.Mode().Perm())
	}
}

func TestResolveOwnerSupportsNumericAndImageNamedIdentities(t *testing.T) {
	uid, gid, err := resolveOwner("65532:65532")
	if err != nil || uid != 65532 || gid != 65532 {
		t.Fatalf("unexpected numeric owner: %d:%d, %v", uid, gid, err)
	}
	uid, gid, err = resolveOwner("root:root")
	if err != nil || uid != 0 || gid != 0 {
		t.Fatalf("unexpected named owner: %d:%d, %v", uid, gid, err)
	}
	if _, _, err = resolveOwner("missing-owner"); err == nil {
		t.Fatal("expected an unavailable owner to be rejected")
	}
}
