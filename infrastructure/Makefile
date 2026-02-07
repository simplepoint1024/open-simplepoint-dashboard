ENV ?= dev

apply:
	terraform init
	terraform apply -var-file="envs/$(ENV).tfvars"
