package tlsidentity

import (
	"crypto/tls"
	"crypto/x509"
	"net/url"
	"testing"
)

func TestVerifiedPeerURIRequiresExactVerifiedIdentity(t *testing.T) {
	identity, err := url.Parse("spiffe://open-simplepoint/ai-control-plane")
	if err != nil {
		t.Fatal(err)
	}
	state := &tls.ConnectionState{
		VerifiedChains: [][]*x509.Certificate{{
			{URIs: []*url.URL{identity}},
		}},
	}
	if !VerifiedPeerURI(state, identity.String()) {
		t.Fatal("expected exact verified URI identity to be accepted")
	}
	if VerifiedPeerURI(
		state,
		"spiffe://open-simplepoint/runtime-node/node-a",
	) {
		t.Fatal("expected a different URI identity to be rejected")
	}
	if VerifiedPeerURI(&tls.ConnectionState{}, identity.String()) {
		t.Fatal("expected an unverified peer to be rejected")
	}
}
