variable "environment" {
  type        = string
  description = "环境标识，例如 dev / test / prod"
  default     = "dev"
}

variable "vault_address" {
  type        = string
  description = "Vault 地址，例如 http://localhost:8200"
}

variable "vault_root_token" {
  type        = string
  description = "Vault 地址，例如 http://localhost:8200"
  sensitive   = true
}
