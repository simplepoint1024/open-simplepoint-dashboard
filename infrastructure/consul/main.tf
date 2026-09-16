provider "consul" {
  address = var.consul_address
  token   = var.consul_master_token
}

locals {
  config_root = "${path.module}/../../config/consul"
  profile     = var.environment
  base_dir    = "${local.config_root}/base"
  profile_dir = "${local.config_root}/profiles/${local.profile}"

  base_configs = {
    for relpath in fileset(local.base_dir, "**/*.properties") :
    relpath => file("${local.base_dir}/${relpath}")
  }
  profile_configs = {
    for relpath in try(fileset(local.profile_dir, "**/*.properties"), toset([])) :
    relpath => file("${local.profile_dir}/${relpath}")
  }
  all_config = merge(local.base_configs, local.profile_configs)
}

resource "consul_keys" "service_config" {
  dynamic "key" {
    for_each = local.all_config
    content {
      path  = key.key
      value = tostring(key.value)
    }
  }
}
