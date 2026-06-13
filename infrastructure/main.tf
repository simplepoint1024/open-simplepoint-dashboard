terraform {
  required_version = ">= 1.5.0"

  required_providers {
    consul = {
      source  = "hashicorp/consul"
      version = "~> 2.20"
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
