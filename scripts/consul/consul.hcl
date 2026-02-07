datacenter = "dc1"
data_dir   = "./data"
bind_addr = "127.0.0.1"
advertise_addr = "127.0.0.1"



server = true
bootstrap_expect = 1

acl {
  enabled        = true
  default_policy = "deny"
  down_policy    = "extend-cache"
  enable_token_persistence = true
}
