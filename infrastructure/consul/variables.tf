variable "environment" {
  type        = string
  description = "环境标识，例如 dev / test / prod"
  default     = "dev"
}


variable "consul_address" {
  type        = string
  description = "Consul 地址，例如 http://127.0.0.1:8500"
}

variable "consul_master_token" {
  type        = string
  description = "Consul 管理员 Token"
  sensitive   = true
}
