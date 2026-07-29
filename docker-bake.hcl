variable "OCI_IMAGE_VERSION" {
  default = "dev"
}

variable "OCI_IMAGE_REVISION" {
  default = "unknown"
}

variable "OCI_IMAGE_SOURCE" {
  default = "https://github.com/simplepoint1024/open-simplepoint-dashboard"
}

variable "SIMPLEPOINT_POSTGRES_IMAGE" {
  default = "somesimpled/open-simplepoint-postgres:local"
}

variable "SIMPLEPOINT_BOOTSTRAP_IMAGE" {
  default = "somesimpled/open-simplepoint-bootstrap:local"
}

variable "SIMPLEPOINT_AUTH_IMAGE" {
  default = "somesimpled/open-simplepoint-authorization:local"
}

variable "SIMPLEPOINT_COMMON_IMAGE" {
  default = "somesimpled/open-simplepoint-common:local"
}

variable "SIMPLEPOINT_AUDITING_IMAGE" {
  default = "somesimpled/open-simplepoint-auditing:local"
}

variable "SIMPLEPOINT_DNA_IMAGE" {
  default = "somesimpled/open-simplepoint-dna:local"
}

variable "SIMPLEPOINT_AI_IMAGE" {
  default = "somesimpled/open-simplepoint-ai:local"
}

variable "SIMPLEPOINT_MCP_GATEWAY_IMAGE" {
  default = "somesimpled/open-simplepoint-mcp-gateway:local"
}

variable "SIMPLEPOINT_TOOL_RUNTIME_IMAGE" {
  default = "somesimpled/open-simplepoint-tool-runtime:local"
}

variable "SIMPLEPOINT_TOOL_EGRESS_PROXY_IMAGE" {
  default = "somesimpled/open-simplepoint-tool-egress-proxy:local"
}

variable "SIMPLEPOINT_TOOL_IMAGE_VERIFIER_IMAGE" {
  default = "somesimpled/open-simplepoint-tool-image-verifier:local"
}

variable "SIMPLEPOINT_RUNTIME_PKI_IMAGE" {
  default = "somesimpled/open-simplepoint-runtime-pki:local"
}

variable "SIMPLEPOINT_HOST_IMAGE" {
  default = "somesimpled/open-simplepoint-host:local"
}

group "default" {
  targets = [
    "postgres",
    "bootstrap",
    "authorization",
    "common",
    "auditing",
    "dna",
    "ai",
    "mcp-gateway",
    "runtime-pki",
    "tool-egress-proxy",
    "tool-image-verifier",
    "tool-runtime",
    "host",
  ]
}

target "_oci" {
  context = "."
  args = {
    OCI_IMAGE_VERSION  = OCI_IMAGE_VERSION
    OCI_IMAGE_REVISION = OCI_IMAGE_REVISION
    OCI_IMAGE_SOURCE   = OCI_IMAGE_SOURCE
  }
}

target "_java" {
  inherits = ["_oci"]
}

target "postgres" {
  inherits   = ["_oci"]
  dockerfile = "docker/postgres/Dockerfile"
  tags       = [SIMPLEPOINT_POSTGRES_IMAGE]
}

target "bootstrap" {
  inherits   = ["_oci"]
  dockerfile = "docker/swarm/bootstrap/Dockerfile"
  tags       = [SIMPLEPOINT_BOOTSTRAP_IMAGE]
}

target "authorization" {
  inherits   = ["_java"]
  dockerfile = "simplepoint-services/simplepoint-service-authorization/Dockerfile"
  tags       = [SIMPLEPOINT_AUTH_IMAGE]
}

target "common" {
  inherits   = ["_java"]
  dockerfile = "simplepoint-services/simplepoint-service-common/Dockerfile"
  tags       = [SIMPLEPOINT_COMMON_IMAGE]
}

target "auditing" {
  inherits   = ["_java"]
  dockerfile = "simplepoint-services/simplepoint-service-auditing/Dockerfile"
  tags       = [SIMPLEPOINT_AUDITING_IMAGE]
}

target "dna" {
  inherits   = ["_java"]
  dockerfile = "simplepoint-services/simplepoint-service-dna/Dockerfile"
  tags       = [SIMPLEPOINT_DNA_IMAGE]
}

target "ai" {
  inherits   = ["_java"]
  dockerfile = "simplepoint-services/simplepoint-service-ai/Dockerfile"
  tags       = [SIMPLEPOINT_AI_IMAGE]
}

target "mcp-gateway" {
  inherits   = ["_java"]
  dockerfile = "simplepoint-services/simplepoint-service-mcp-gateway/Dockerfile"
  tags       = [SIMPLEPOINT_MCP_GATEWAY_IMAGE]
}

target "tool-runtime" {
  inherits   = ["_oci"]
  dockerfile = "simplepoint-services/simplepoint-service-tool-runtime-node/Dockerfile"
  tags       = [SIMPLEPOINT_TOOL_RUNTIME_IMAGE]
}

target "tool-egress-proxy" {
  inherits   = ["_oci"]
  dockerfile = "simplepoint-services/simplepoint-service-tool-egress-proxy/Dockerfile"
  tags       = [SIMPLEPOINT_TOOL_EGRESS_PROXY_IMAGE]
}

target "tool-image-verifier" {
  inherits   = ["_oci"]
  dockerfile = "simplepoint-services/simplepoint-service-tool-image-verifier/Dockerfile"
  tags       = [SIMPLEPOINT_TOOL_IMAGE_VERIFIER_IMAGE]
}

target "runtime-pki" {
  inherits   = ["_oci"]
  dockerfile = "docker/runtime-pki/Dockerfile"
  tags       = [SIMPLEPOINT_RUNTIME_PKI_IMAGE]
}

target "host" {
  inherits   = ["_java"]
  dockerfile = "simplepoint-services/simplepoint-service-host/Dockerfile"
  tags       = [SIMPLEPOINT_HOST_IMAGE]
}
