// 指定Vault的Transit后端路径
variable "vault_transit_mount_path" {
  description = "Transit backend path"
  type        = string
  default     = "transit"
}
