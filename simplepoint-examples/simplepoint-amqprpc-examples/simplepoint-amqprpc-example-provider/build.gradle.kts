subprojects {
    group = "org.simplepoint.example.amqprpc.provider"
}

dependencies {
    implementation(project(":simplepoint-boot:simplepoint-boot-config-consul-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.springframework.cloud:spring-cloud-starter-consul-discovery")
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")

    api(project(":simplepoint-plugin:simplepoint-plugin-webmvc"))

    api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-rpc"))
    api(project(":simplepoint-examples:simplepoint-amqprpc-examples:simplepoint-amqprpc-example-api"))
}