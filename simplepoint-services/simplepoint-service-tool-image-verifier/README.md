# SimplePoint Tool Image Verifier

This service is the independent OCI supply-chain admission boundary used by
`tool-runtime-node`. It does not execute MCP code and can be deployed or scaled
without changing the AI service, MCP Gateway, or runtime node binary.

For each digest-pinned image it fails closed unless all enabled checks pass:

- Cosign image signature verification using a configured public key or a
  keyless certificate identity and OIDC issuer;
- a signed SPDX or CycloneDX SBOM attestation bound to the same image digest;
- a Trivy vulnerability scan with no findings at the configured blocked
  severities.

The service also exposes Cosign signature admission for digest-pinned,
non-runnable OCI Artifacts. Artifact content and media types are checked by the
control-plane Registry client before signature admission.

Requests use TLS 1.3 mutual authentication. Image admission accepts only
certificates with the URI SAN prefix
`spiffe://open-simplepoint/runtime-node/`; Artifact admission accepts only the
exact `spiffe://open-simplepoint/ai-control-plane` identity. The server
identity is `spiffe://open-simplepoint/tool-image-verifier`. Decisions are
cached by immutable digest and policy hash for a short TTL. Every replica uses
the same static policy, so replicas are horizontally scalable.

The production policy should set either:

```text
SIMPLEPOINT_TOOL_IMAGE_VERIFIER_SIGNATURE_MODE=key
SIMPLEPOINT_TOOL_IMAGE_VERIFIER_COSIGN_PUBLIC_KEY_FILE=/run/policy/cosign.pub
```

or:

```text
SIMPLEPOINT_TOOL_IMAGE_VERIFIER_SIGNATURE_MODE=keyless
SIMPLEPOINT_TOOL_IMAGE_VERIFIER_CERTIFICATE_IDENTITY_REGEXP=...
SIMPLEPOINT_TOOL_IMAGE_VERIFIER_CERTIFICATE_OIDC_ISSUER=https://...
```

The private APIs are split by workload identity:

```text
POST /internal/v1/images/verify
  caller: spiffe://open-simplepoint/runtime-node/{nodeId}

POST /internal/v1/artifacts/verify
  caller: spiffe://open-simplepoint/ai-control-plane
```

Plain HTTP Registries and skipped transparency-log verification are local-test
escape hatches only. Both remain disabled by default:

```text
SIMPLEPOINT_TOOL_IMAGE_VERIFIER_ALLOW_HTTP_REGISTRY=false
SIMPLEPOINT_TOOL_IMAGE_VERIFIER_IGNORE_TRANSPARENCY_LOG=false
```

The image pins the Cosign and Trivy tool images by OCI index digest. The Trivy
cache is the only writable persistent path; the service itself runs as UID
`65532` with a read-only root filesystem in Compose.
