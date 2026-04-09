dependencies {
    implementation(project(":simplepoint-core"))
    implementation("com.esotericsoftware:kryo:5.6.2")
    implementation("io.micrometer:micrometer-core")
    api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-core"))
    api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-annotation"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
