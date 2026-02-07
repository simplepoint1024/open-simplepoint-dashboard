provider "consul" {
  address = var.consul_address
  token   = var.consul_master_token
}

locals {
  services_dir = "${path.module}/config"
  service_configs = {
    for relpath in fileset(local.services_dir, "**") :
    relpath => file("${local.services_dir}/${relpath}")
  }

  all_config = {
    for relpath, content in local.service_configs :
    relpath => content
  }
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
