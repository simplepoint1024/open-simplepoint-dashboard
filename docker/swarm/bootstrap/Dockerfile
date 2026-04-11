FROM alpine:3.22

RUN apk add --no-cache bash curl ca-certificates

WORKDIR /bootstrap

COPY docker/swarm/bootstrap/init-swarm.sh /bootstrap/init-swarm.sh
COPY docker/swarm/bootstrap/consul-config /bootstrap/consul-config

RUN chmod +x /bootstrap/init-swarm.sh

ENTRYPOINT ["/bootstrap/init-swarm.sh"]
