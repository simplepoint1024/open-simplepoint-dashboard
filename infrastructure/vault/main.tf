// 配置 Vault 提供者
provider "vault" {
  address = var.vault_address
  token   = var.vault_root_token
}

// 启用 Transit Secrets Engine
resource "vault_mount" "transit" {
  path = "transit"
  type = "transit"
}

// 引入 Transit 模块
module "transit" {
  source = "./transit"
  vault_transit_mount_path = vault_mount.transit.path
}