package tlsidentity

import (
	"crypto/tls"
	"crypto/x509"
	"net/url"
	"testing"
)

func TestVerifiedRuntimeNodeRequiresVerifiedExactPrefix(t *testing.T) {
	identity, err := url.Parse(
		"spiffe://open-simplepoint/runtime-node/node-a",
	)
	if err != nil {
		t.Fatal(err)
	}
	state := &tls.ConnectionState{
		VerifiedChains: [][]*x509.Certificate{{
			{URIs: []*url.URL{identity}},
		}},
	}
	nodeID, ok := VerifiedRuntimeNode(
		state,
		"spiffe://open-simplepoint/runtime-node/",
	)
	if !ok || nodeID != "node-a" {
		t.Fatalf("unexpected verified node: %q %v", nodeID, ok)
	}
	if _, ok = VerifiedRuntimeNode(
		state,
		"spiffe://open-simplepoint/runtime-nodes/",
	); ok {
		t.Fatal("expected a different identity prefix to be rejected")
	}
}

func TestVerifiedIdentityRequiresExactVerifiedURI(t *testing.T) {
	identity, err := url.Parse(
		"spiffe://open-simplepoint/ai-control-plane",
	)
	if err != nil {
		t.Fatal(err)
	}
	state := &tls.ConnectionState{
		VerifiedChains: [][]*x509.Certificate{{
			{URIs: []*url.URL{identity}},
		}},
	}
	if !VerifiedIdentity(
		state,
		"spiffe://open-simplepoint/ai-control-plane",
	) {
		t.Fatal("expected exact AI identity to be accepted")
	}
	if VerifiedIdentity(
		state,
		"spiffe://open-simplepoint/ai-control-planes",
	) {
		t.Fatal("expected a different AI identity to be rejected")
	}
}
