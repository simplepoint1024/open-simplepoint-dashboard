subprojects {
    group = "org.simplepoint.example.amqprpc.consumer"
}

dependencies{
    api(project(":simplepoint-core"))
    api(project(":simplepoint-boot:simplepoint-boot-starter-web"))
    api(project(":simplepoint-cloud:simplepoint-cloud-consul"))
    api(project(":simplepoint-cloud:simplepoint-cloud-loadbalancer"))
    api(project(":simplepoint-plugin:simplepoint-plugin-webmvc"))

    api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-rpc"))
    api(project(":simplepoint-examples:simplepoint-amqprpc-examples:simplepoint-amqprpc-example-api"))
}