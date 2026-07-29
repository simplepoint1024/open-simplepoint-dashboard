// Package tlsidentity builds strict TLS configurations for Runtime node links.
package tlsidentity

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net/url"
	"os"
)

// ClientConfig loads a client certificate and a private server trust root.
func ClientConfig(
	certificateFile string,
	privateKeyFile string,
	caFile string,
	serverName string,
	localIdentity string,
) (*tls.Config, error) {
	certificate, leaf, roots, err := load(
		certificateFile,
		privateKeyFile,
		caFile,
	)
	if err != nil {
		return nil, err
	}
	if !certificateHasURI(leaf, localIdentity) {
		return nil, fmt.Errorf(
			"TLS certificate does not contain identity %q",
			localIdentity,
		)
	}
	return &tls.Config{
		MinVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{certificate},
		RootCAs:      roots,
		ServerName:   serverName,
	}, nil
}

// ServerConfig loads a server certificate and verifies presented client roots.
//
// Client certificates are requested at the TLS layer so /health can remain
// usable by the local container healthcheck. Private API middleware must call
// VerifiedPeerURI to require the expected authenticated identity.
func ServerConfig(
	certificateFile string,
	privateKeyFile string,
	caFile string,
	localIdentity string,
) (*tls.Config, error) {
	certificate, leaf, roots, err := load(
		certificateFile,
		privateKeyFile,
		caFile,
	)
	if err != nil {
		return nil, err
	}
	if !certificateHasURI(leaf, localIdentity) {
		return nil, fmt.Errorf(
			"TLS certificate does not contain identity %q",
			localIdentity,
		)
	}
	return &tls.Config{
		MinVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{certificate},
		ClientAuth:   tls.VerifyClientCertIfGiven,
		ClientCAs:    roots,
	}, nil
}

// VerifiedPeerURI reports whether a verified peer chain owns an exact URI SAN.
func VerifiedPeerURI(state *tls.ConnectionState, expected string) bool {
	if state == nil || len(state.VerifiedChains) == 0 {
		return false
	}
	for _, chain := range state.VerifiedChains {
		if len(chain) > 0 && certificateHasURI(chain[0], expected) {
			return true
		}
	}
	return false
}

func load(
	certificateFile string,
	privateKeyFile string,
	caFile string,
) (tls.Certificate, *x509.Certificate, *x509.CertPool, error) {
	certificate, err := tls.LoadX509KeyPair(certificateFile, privateKeyFile)
	if err != nil {
		return tls.Certificate{}, nil, nil, fmt.Errorf(
			"load TLS certificate: %w",
			err,
		)
	}
	if len(certificate.Certificate) == 0 {
		return tls.Certificate{}, nil, nil, errors.New(
			"TLS certificate chain is empty",
		)
	}
	leaf, err := x509.ParseCertificate(certificate.Certificate[0])
	if err != nil {
		return tls.Certificate{}, nil, nil, fmt.Errorf(
			"parse TLS leaf certificate: %w",
			err,
		)
	}
	certificate.Leaf = leaf
	caPEM, err := os.ReadFile(caFile)
	if err != nil {
		return tls.Certificate{}, nil, nil, fmt.Errorf(
			"read TLS CA certificate: %w",
			err,
		)
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(caPEM) {
		return tls.Certificate{}, nil, nil, errors.New(
			"TLS CA certificate is invalid",
		)
	}
	return certificate, leaf, roots, nil
}

func certificateHasURI(certificate *x509.Certificate, expected string) bool {
	identity, err := url.Parse(expected)
	if err != nil || identity.Scheme == "" {
		return false
	}
	for _, candidate := range certificate.URIs {
		if candidate.String() == identity.String() {
			return true
		}
	}
	return false
}
