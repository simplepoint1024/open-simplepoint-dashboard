// 创建 RSA 密钥对，用于 JWT 签名
resource "vault_transit_secret_backend_key" "sas_jwt" {
  backend = var.vault_transit_mount_path   # 自动使用真实路径
  name    = "sas-jwt"
  type                    = "rsa-2048"
  exportable              = true
  allow_plaintext_backup  = true
  deletion_allowed        = true
}
