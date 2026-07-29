package supplychain

import (
	"testing"
)

func TestResultCarriesFailClosedAdmissionEvidence(t *testing.T) {
	result := Result{
		Image:             "docker.io/example/mcp@sha256:test",
		Admitted:          true,
		SignatureVerified: true,
		SBOMVerified:      true,
		PolicyHash:        "sha256:policy",
	}
	if !result.Admitted || !result.SignatureVerified || !result.SBOMVerified {
		t.Fatal("expected complete admission evidence")
	}
}
