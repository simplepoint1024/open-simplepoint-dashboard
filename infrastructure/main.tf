terraform {
  required_version = ">= 1.5.0"

  required_providers {
    consul = {
      source  = "hashicorp/consul"
      version = "~> 2.20"
    }
    vault = {
      source  = "hashicorp/vault"
      version = "5.3.0"
    }
  }
}

module "consul_acl" {
  source = "./consul"

  # 把变量传给子模块
  environment         = var.environment
  consul_address      = var.consul_address
  consul_master_token = var.consul_master_token
}

module "vault_acl" {
  source = "./vault"

  # 把变量传给子模块
  environment      = var.environment
  vault_address    = var.vault_address
  vault_root_token = var.vault_root_token
}
